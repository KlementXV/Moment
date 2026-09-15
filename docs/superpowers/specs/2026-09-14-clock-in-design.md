# Clock In — Design

Hackathon Solana Mobile « Clock In » (Radiants). Cible : Seeker / dApp Store Android.
Date : 2026-09-14.

## 1. Vision

Un BeReal pour la communauté Seeker, où chaque publication quotidienne est
horodatée on-chain et où ta régularité a une valeur économique en SKR.

Tu stakes du SKR pour entrer. Chaque jour où tu « clockes in », ton solde grossit
un peu. Chaque jour manqué pendant que ta mise est active, il perd 25 % ou 50 %
(taux à calibrer) — et ce qui est perdu alimente ceux qui sont restés réguliers.
Quitter le jeu demande 48 h : on ne peut pas retirer sa mise du jour au lendemain
pour éviter une absence. L'économie est une boucle fermée : ta discipline est
payée par le relâchement des autres.

## 2. Boucle quotidienne

1. Tu ouvres l'app quand tu veux dans la journée (pas de notification aléatoire
   imposée — variante assumée par rapport à BeReal).
2. Capture double caméra avant + arrière, quasi-simultanée.
3. L'app normalise l'orientation, réencode les pixels sans métadonnées et
   contrôle les deux photos localement (§7 et §8.6), avant tout envoi réseau.
4. Le wallet signe via MWA un manifeste contenant les hashes des deux images
   nettoyées. Le hash de ce manifeste devient le commitment on-chain.
5. Chiffrement local avec une clé aléatoire, puis upload en zone de quarantaine
   et dépôt authentifié de la clé pour la vérification serveur (§8.6).
6. Le backend vérifie les octets signés et la modération, puis délivre une
   autorisation de publication liée au commitment, au wallet et au jour.
7. Transaction `check_in` signée via MWA : le programme vérifie cette autorisation,
   inscrit le commitment et la référence de stockage, et actualise solde et streak.
8. Après confirmation on-chain, le backend autorise la distribution des clés
   et le feed se débloque pour la journée.

## 3. Les piliers

| Pilier | Contenu |
|---|---|
| Social | Feed quotidien global de la communauté Seeker, gaté par ton propre check-in |
| Provenance | Capture verrouillée + commitment horodaté on-chain (voir §7) |
| Confidentialité | Images chiffrées ; clés séquestrées et délivrées aux membres (voir §8) |
| Économie | Mise SKR, rendement quotidien, decay de 25 % ou 50 % par jour manqué, retrait différé de 48 h |

## 4. Architecture

Quatre composants. Le stockage IPFS décrit ci-dessous reste provisoire :
un bucket privé chiffré est proposé (§15.4). Les contrôles de signature et de
modération s'appliquent aux deux options.

- **Programme Anchor** (`program/`, nom `clockin`) — source de vérité : profils,
  soldes, streaks, commitments, pool de redistribution.
- **App Android Kotlin/Compose** (`app/`) — capture, chiffrement, hash, signature
  via Mobile Wallet Adapter, lecture du feed, UI.
- **IPFS** (Pinata, free tier) — stockage des blobs **chiffrés**. Le CID reste
  une adresse de contenu, donc une preuve d'intégrité du blob transporté.
- **Serveur de clés** (`keyserver/`) — séquestre des clés de post, délivrées
  contre une preuve de check-in signée. Le backend assure aussi la vérification
  des publications et la modération (§8.6) : ce rôle de confiance est explicite.

Lecture du feed : `getProgramAccounts` filtré sur le jour courant, via RPC Helius
devnet. Les blobs sont récupérés depuis IPFS par CID, les clés depuis le serveur.

## 5. Modèle de données on-chain

Décision structurante : **tout le SKR (stakes de tous les utilisateurs + pool de
redistribution) vit dans un seul vault SPL**. Le decay et les récompenses sont de
la pure comptabilité dans les comptes ; aucun transfert SPL n'a lieu. Seuls
`stake` et `finalize_exit` déplacent réellement des tokens (`tip` reste une
écriture comptable). Moins cher, moins de surface de bug.

### Comptes

**`Config`** (PDA singleton)

| Champ | Type | Rôle |
|---|---|---|
| `admin` | Pubkey | autorité de configuration |
| `skr_mint` | Pubkey | mint SKR (devnet : mint de test, swappable en mainnet) |
| `vault` | Pubkey | token account détenu par le PDA Config |
| `pool_balance` | u64 | part du vault qui appartient au pool de redistribution |
| `min_stake` | u64 | solde effectif minimum pour accéder aux fonctions sociales |
| `reward_rate_bps` | u16 | rendement quotidien (ex. 100 = 1 % du stake) |
| `reward_cap` | u64 | plafond absolu par check-in (anti-baleine) |
| `decay_bps` | u16 | perte par jour manqué (2500 = 25 %, 5000 = 50 %) |
| `max_decay_days` | u8 | borne d'itération ; au-delà le solde tombe à 0 |
| `withdrawal_delay_seconds` | i64 | délai de sortie : 172 800 s (48 h) en v1 |

**`Profile`** (PDA par wallet)

| Champ | Type | Rôle |
|---|---|---|
| `owner` | Pubkey | |
| `staked` | u64 | solde unique : sert de stake ET de compteur de gains |
| `active` | bool | position ouverte ; une sortie finalisée la ferme |
| `settled_day` | i64 | dernier jour dont la comptabilité est à jour |
| `last_checkin_day` | i64 | dernier jour réellement posté (affichage / streak) |
| `streak` | u32 | |
| `total_checkins` | u64 | |
| `exit_requested_at` | i64 | timestamp de demande ; 0 si aucune sortie en cours |
| `exit_unlock_at` | i64 | timestamp de déblocage figé à la demande ; 0 sinon |

Deux champs de date distincts : `settled_day` pilote le decay, `last_checkin_day`
décrit l'activité. Cette séparation est ce qui rend `reap` (§6.4) sûr contre le
double-decay.

**`CheckIn`** (PDA par couple wallet × jour)

| Champ | Type | Rôle |
|---|---|---|
| `owner` | Pubkey | |
| `day` | i64 | jour UTC |
| `commitment` | [u8; 32] | SHA-256 du manifeste canonique signé, qui contient les hashes des images nettoyées (§7) |
| `cid` | String (≤64) | CID IPFS du blob **chiffré** |
| `slot` | u64 | slot d'inscription, l'ancre temporelle |
| `streak_at_checkin` | u32 | |

Le PDA par couple wallet × jour rend le double check-in structurellement
impossible : le compte existe déjà.

### Instructions

`initialize_config`, `create_profile`, `stake`, `check_in`, `reap`,
`request_exit`, `cancel_exit`, `finalize_exit`, `faucet` (devnet uniquement,
§10), et `tip` en stretch.

Sur une position active, `stake` règle les jours manqués **avant** d'ajouter le
nouveau dépôt : aucun token fraîchement déposé ne subit un decay rétroactif.
Après une sortie finalisée, un nouveau `stake` ouvre une position avec
`settled_day = today - 1` et `streak = 0`. Le dépôt est interdit pendant une
sortie en cours pour garder un montant de sortie lisible.

## 6. Économie — règles précises

Le jour est dérivé du sysvar `Clock` (`unix_timestamp / 86_400`), jamais passé en
paramètre par le client.

Une même routine `settle_missed(profile, through_day)` applique le decay des
jours non réglés jusqu'à `through_day` inclus. `check_in`, `stake`, `request_exit`,
`reap` et `finalize_exit` l'appellent avec la borne adaptée ; une journée encore
en cours n'est jamais comptée comme manquée.

### 6.1 `check_in`

1. Exiger une position active. Si une sortie est en cours, le check-in reste
   possible seulement tant que `now < exit_unlock_at`. Vérifier aussi une
   autorisation de publication authentifiée du backend, liée au wallet, au
   commitment, à la référence de stockage et au jour courant (§8.6).
2. Régler les jours manqués jusqu'à `today - 1`, soit initialement
   `missed = today - settled_day - 1`.
3. Si `missed > 0` : `staked` décimé de `decay_bps` par jour manqué (borné par
   `max_decay_days`, au-delà → 0). Le montant perdu est ajouté à `pool_balance`.
   `streak = 0`.
4. Si `staked < min_stake` → refus (`InsufficientStake`). Il faut recharger pour
   rejouer. C'est le mécanisme « stake pour poster, donc pour voir ».
5. `reward = min(staked × reward_rate_bps / 10_000, reward_cap, pool_balance)`
   puis `pool_balance -= reward`, `staked += reward`.
6. `streak += 1`, `settled_day = today`, `last_checkin_day = today`,
   `total_checkins += 1`.
7. Création du compte `CheckIn` avec le commitment et le CID.

Si le pool est vide, `reward = 0` : le check-in réussit quand même et le streak
progresse. L'économie ne bloque jamais la boucle sociale.

### 6.2 Amorçage

Le pool démarre vide : tant que personne n'a manqué un jour, personne ne gagne.
On l'amorce donc par un dépôt initial de l'admin au déploiement, explicitement
présenté comme tel dans le pitch (en production, ce serait le sujet du business
model).

### 6.3 Sortie différée

Le retrait est **total** en v1 ; pas de retrait partiel. Il comporte trois
instructions, pour que le vault reste le seul détenteur des tokens jusqu'au
déblocage :

`now` est le `unix_timestamp` du sysvar `Clock`. Une seule demande peut être
ouverte à la fois.

1. `request_exit` règle d'abord les jours déjà manqués jusqu'à hier, puis inscrit
   `exit_requested_at = now` et
   `exit_unlock_at = now + withdrawal_delay_seconds`. Le délai est figé pour
   cette demande, même si la configuration change ensuite.
2. Pendant l'attente, la position et son solde restent actifs : l'utilisateur
   peut encore publier et éviter le decay. Il peut annuler la demande avec
   `cancel_exit` **avant** l'heure de déblocage ; une nouvelle demande repart de
   zéro. Annuler ne restitue jamais les pertes déjà appliquées.
3. Dès `exit_unlock_at`, la position cesse d'être exposée. `finalize_exit` est
   permissionless, transfère le solde restant vers le compte SKR du propriétaire,
   met `staked = 0`, `active = false`, `streak = 0`, efface la demande et ferme la
   position. Il ne peut être appelé qu'après le déblocage. Même si personne ne
   l'appelle immédiatement, aucun jour supplémentaire n'est pénalisé.

La dernière journée pénalisable est celle **entièrement terminée avant** l'heure
de déblocage : `unlock_day - 1`, où `unlock_day = exit_unlock_at / 86_400`.
`finalize_exit` règle donc au plus jusqu'à cette journée. Si l'utilisateur a
publié pendant l'attente, ces jours sont déjà réglés. Le transfert est toujours
adressé au compte de tokens du propriétaire, jamais à celui de l'appelant.

Exemple : check-in lundi, demande de sortie lundi, aucun autre check-in,
déblocage mercredi à la même heure. Seul mardi est manqué : 10 SKR deviennent
7,5 SKR à 25 %, ou 5 SKR à 50 %. Si l'utilisateur publie mardi, il peut
retirer sans perte de decay mercredi. **Le délai n'est donc pas une taxe de
sortie garantie** : une éventuelle commission explicite serait une décision
produit distincte, absente de la v1.

### 6.4 `reap` — le trou de la boucle fermée

Problème identifié : si un utilisateur ne revient jamais, son solde n'est jamais
décimé et le pool ne reçoit rien. La boucle fermée s'assèche, puisqu'elle ne
s'alimente que du retour des joueurs en retard.

Solution : `reap(profile)`, instruction **permissionless**. N'importe qui peut
déclencher le decay d'un profil en retard.

- Exige au moins un jour non réglé. Pour une sortie en attente ou débloquée, la
  borne est `min(today - 1, unlock_day - 1)` : impossible de pénaliser après la
  fin effective de la position.
- Applique le même decay, pousse le montant perdu vers `pool_balance`.
- `settled_day` avance jusqu'à cette borne, `streak = 0`.

Conséquence : si elle check-in ensuite le même jour, `missed = 0` et aucun decay
supplémentaire n'est appliqué. Pas de double-decay.

Bonus au reaper (part du montant récupéré) : optionnel, phase 3.

### 6.5 `tip` (stretch)

Transfert de `staked` entre deux profils. Pure comptabilité, pas de transfert
SPL. Réaction sociale sur un post du feed.

## 7. Provenance de la capture — cadrage et menaces

Deux des juges sont chercheurs en sécurité (Voynich, a2nkf — Ethelsec).
Revendiquer « anti-IA » serait à la fois faux et contre-productif. Le
positionnement retenu est **provenance attestée + horodatage on-chain**.

### Ce qui est construit

- Capture via CameraX uniquement, double caméra quasi-simultanée.
- **Aucun chemin d'import galerie n'existe dans l'app.**
- Nettoyage **avant hash, signature, chiffrement et tout upload** : appliquer
  l'orientation aux pixels, réencoder dans un nouveau fichier sans recopier les
  EXIF, XMP, IPTC, miniatures embarquées ou commentaires. Aucun GPS, altitude,
  identifiant d'appareil, numéro de série, date de capture précise ou nom de
  fichier d'origine ne doit être transmis. Vérifier le fichier réencodé ; enlever
  seulement quelques tags GPS ne suffit pas. Ne pas demander de permission GPS.
- Les fichiers bruts restent dans le stockage privé temporaire de l'app, sont
  exclus des sauvegardes et des journaux, puis supprimés après traitement.
- Manifeste canonique versionné : domaine `clockin-post-v1`, réseau, programme,
  wallet, jour UTC, nonce aléatoire et SHA-256 de chaque image nettoyée. Spécifier
  l'encodage exact des champs avant implémentation (pas de concaténation ambiguë).
  La signature détachée Ed25519 demandée au wallet via MWA porte sur ces octets.
  Le manifeste et sa signature accompagnent les images dans le blob chiffré.
- `commitment = SHA-256(manifest_bytes)` ; la transaction de check-in engage ce
  même commitment. Le serveur vérifie les hashes et la signature du propriétaire.
  Toute modification ultérieure des pixels exige une nouvelle signature.
- Le hash est calculé avant l'upload ; l'ancrage temporel on-chain a lieu lors de
  la transaction suivante. La preuve ne certifie pas l'heure de prise de vue.
- Vérifiable par un tiers : récupérer le CID, recalculer le hash, comparer au
  `commitment`, lire le slot.

### Ce qui est prouvé

Cette paire d'images exacte existait à cet instant et a été engagée par ce wallet,
horodatée par le réseau.

### Ce qui n'est pas prouvé

Qu'un capteur a observé la réalité. Un device rooté ou une caméra virtuelle peut
injecter des frames. La double capture simultanée élève le coût de la fraude
(il faut deux flux cohérents), sans l'éliminer.

La suppression des métadonnées ne masque pas une adresse, un badge, un document
ou un lieu reconnaissable visible dans les pixels. Prévoir une alerte locale et
une possibilité de reprendre la photo. Un floutage éventuel intervient avant
la signature. Le wallet et le jour de publication restent des données publiques
corrélables ; ne pas promettre l'anonymat ni l'absence totale de géolocalisation.

### Renforcement (stretch)

Signature **Seed Vault** : lie la preuve à l'élément sécurisé matériel du Seeker,
pas seulement à un wallet logiciel. Peu d'équipes toucheront au Seed Vault —
fort potentiel sur le critère Innovation / X-factor, et cohérent avec le narratif
« communauté Seeker ». À prototyper tôt (SDK peu documenté).

## 8. Confidentialité des images et serveur de clés

> **Décision provisoire — à réexaminer (§15.1).** Le schéma ci-dessous est retenu
> pour pouvoir avancer, mais l'arbitrage sur la garde des clés n'est pas figé.

### 8.1 Le problème

Sans chiffrement, les images seraient lisibles par n'importe qui, y compris des
personnes n'ayant jamais installé l'app : les CID sont inscrits dans les comptes
`CheckIn`, et les comptes Solana sont publics. Un script trivial suffirait à
aspirer l'intégralité des photos, indéfiniment.

Pour une app où l'on publie son visage chaque jour, c'est inacceptable — et
contradictoire avec un produit dont le discours est « ce contenu est humain » :
on constituerait un jeu de données de visages horodatés, ouvert et permanent.

Une clé en dur dans l'app ne résout rien : le règlement du hackathon impose que
le dépôt soit accessible aux juges, la clé serait donc publiée avec le code.

### 8.2 Schéma retenu

Le chiffrement et IPFS sont orthogonaux : on stocke des blobs chiffrés sur IPFS.

À la publication :

1. Nettoyage et contrôle local, puis manifeste et signature du wallet (§7).
2. Tirage d'une clé aléatoire `post_key` (AES-256-GCM, nonce 96 bits aléatoire).
3. Chiffrement du paquet images + manifeste + signature, puis upload. La clé est
   confiée au backend via une session wallet authentifiée, dans un état
   `pending_review` : aucun membre ne peut encore la récupérer. Seuls les octets
   nettoyés sont susceptibles d'être déchiffrés pour vérification.
4. Contrôle serveur puis autorisation de publication (§8.6), liée à la référence
   du blob exact. Le programme vérifie cette autorisation dans `check_in`.
5. Après confirmation du `CheckIn` correspondant, l'état passe à `published` et
   les clés deviennent distribuables aux membres autorisés. Conserver localement
   un état de reprise chiffré pour les échecs réseau ; les étapes sont idempotentes.

À la lecture, le backend vérifie la propriété du wallet, une position encore
active avec un solde effectif >= `min_stake` après decay dû, et le `CheckIn` du
jour. Il délivre uniquement les clés des posts publiés et non retirés du feed.
Les accès wallet, recharge et retrait restent disponibles sous le minimum.
Une clé déjà délivrée ne peut pas être reprise au lecteur.

C'est le client qui génère la clé. Le backend détient cependant la capacité de
la déchiffrer et de la distribuer : ce schéma n'est pas un chiffrement de bout en
bout excluant l'opérateur. Il fonctionne avec IPFS ou un bucket privé.

### 8.3 Divulgation sélective

Le poster détient sa propre clé et peut la révéler volontairement pour rendre un
post publiquement vérifiable par un tiers — utile en cas de contestation, et
c'est le mécanisme utilisé dans la vidéo de démo pour montrer la chaîne de
vérification complète à l'écran.

### 8.4 Modèle de menace

| Adversaire | Résultat |
|---|---|
| Non-membre, crawler, indexeur | Ne voit que des blobs chiffrés. Bloqué. |
| Membre à jour | Accède aux clés du jour — et peut les exfiltrer. Non empêchable. |
| Opérateur du serveur de clés | Peut tout déchiffrer. **Composant de confiance assumé.** |

Le backend détient les clés et exerce un rôle de modérateur : il peut lire les
photos, refuser leur publication et proposer des sanctions. Le programme impose
les plafonds et transitions autorisés, mais ne peut pas juger seul le contenu
d'une image. Cette autorité de modération est un composant de confiance distinct
du contrôle du vault ; son accès ne doit pas permettre de retirer librement les
fonds des utilisateurs.

Une panne du backend empêche les nouvelles autorisations de publication et la
lecture des photos. Elle ne doit pas bloquer les demandes de retrait. La règle
applicable au decay pendant une panne générale doit être arrêtée avant mainnet
(§15.5) : dépendre du backend pour publier change le modèle de disponibilité.

### 8.5 Implémentation

Backend TypeScript avec authentification wallet, sessions courtes, stockage des
clés, états de publication et dossiers de modération. L'estimation précédente de
« ~150 lignes / une table de clés » ne couvre plus ce périmètre. Le stockage des
décisions et des nonces doit permettre des écritures atomiques et empêcher les
rejeux. Ne journaliser ni images, ni clés, ni métadonnées de capture.

### 8.6 Contrôle des images et infractions

**Avant tout envoi :** un classifieur embarqué examine les deux photos nettoyées.
LiteRT est une piste d'exécution Android ; le modèle, sa licence, ses classes,
sa taille et ses faux positifs doivent être évalués sur Seeker. Le runtime ne
fournit pas à lui seul un détecteur de contenu inapproprié. Les catégories
interdites retenues sont le contenu sexuel et la violence. Préciser les
frontières avant activation : sexualité explicite/nudité, violence graphique,
scènes de sport, soins médicaux, art, etc. Le « … » ne doit pas devenir une
catégorie de sanction arbitraire.

Une alerte locale propose de reprendre la photo. Elle ne crée pas une infraction
financière : elle peut être fausse et l'utilisateur peut être en train de corriger
sa capture. Aucun envoi automatique de la photo originale à un prestataire.

**Avant publication :** le contrôle local étant contournable, le backend vérifie
les mêmes images nettoyées, leurs hashes, la signature du wallet et la politique
applicable. Un modèle vision distant peut servir de repli, avec information de
l'utilisateur sur ce transfert et une politique de conservation du fournisseur
vérifiée avant intégration. Une réponse de modèle est un signal de modération,
pas une autorisation autonome de débiter des SKR. Le texte visible dans une
image est traité comme du contenu non fiable, jamais comme une instruction.

Pour empêcher un client modifié de publier directement et de toucher une
récompense, `check_in` exige une autorisation du backend authentifiée par le
programme, bornée dans le temps et liée à ce post exact. Choisir avant codage
entre une co-signature de transaction et une attestation Ed25519 vérifiée ; un
simple booléen « accepté » fourni par le client n'est pas une preuve.

**Trois tentatives par jour UTC ; sanction au troisième refus confirmé :**

Une tentative correspond à une soumission explicite de la paire de photos,
identifiée et signée par le wallet. Deux photos refusées dans la même paire ne
comptent que pour une tentative. Une reprise de capture ou une évaluation du
preview ne compte pas. Une erreur réseau ou une analyse indécise ne compte pas.

| Refus confirmés du jour | Conséquence |
|---|---|
| 1 | Photo refusée, motif et invitation à reprendre |
| 2 | Photo refusée et avertissement : « Prochain refus : perte de 10 % de ta mise et accès suspendu jusqu'à demain » |
| 3 | Infraction : perte de 10 % de la mise et interdiction de publier ou de recevoir les clés du feed jusqu'au prochain jour UTC |

Cette règle remplace les deux infractions cumulatives précédemment proposées.
Les tentatives conformes ne sont pas sanctionnées. Le compteur repart à zéro au
changement de jour UTC ; le bannissement finit à cette même frontière. Le
programme applique au maximum une sanction de modération par wallet et par jour.

L'assiette des 10 % est le solde `staked` après règlement des jours manqués, au
moment de la décision exécutable, avec calcul entier en unités de token.
La destination proposée des fonds est le pool de redistribution ; elle reste à
valider avant activation. La sanction est distincte du decay pour absence : si
le bannissement empêche le check-in du jour, le decay habituel s'ajoutera à la
clôture de ce jour, sauf décision contraire explicite (§15.5).

Le statut doit être vérifié par le programme dans `check_in` et par le backend
lors de toute distribution de clés, y compris si un CheckIn existe déjà ce jour.
Une clé/photo reçue avant le blocage reste copiable ; aucune révocation rétroactive
n'est promise. Les écrans de wallet, recharge, retrait et contestation restent
accessibles. Les API refusent de nouvelles tentatives après le troisième refus.

**Autorité et faux positifs :** le compteur faisant foi ne peut pas être conservé
uniquement sur le téléphone : un client modifié pourrait le réinitialiser.
Un contrôle local bloquant et sans envoi serveur est une aide privée ; ses
alertes seules ne peuvent pas justifier un débit on-chain. Pour compter un refus
sanctionnable, le backend doit vérifier la tentative signée et la politique sur
les images nettoyées en quarantaine. Ce compromis entre confidentialité locale
et sanction vérifiable est explicite.

**Vérification admin obligatoire, décision retenue :** le modèle signale et peut
mettre provisoirement une soumission en attente, puis l'admin confirme ou rejette
le signalement. Seuls les refus confirmés par l'admin alimentent le compteur
sanctionnable. Les trois refus justifiant les -10 % doivent donc avoir été
vérifiés ; une réponse IA seule ne déclenche aucun débit.

Une interface admin privée présente les deux images nettoyées, leur signature
vérifiée, le motif proposé, la version du modèle/de la politique et les décisions
du jour. L'admin peut approuver la publication ou confirmer le refus avec un
motif. Ses actions sont authentifiées et journalisées sans images ni clés dans
les logs. L'autorité de modération ne dispose pas d'un retrait libre du vault.
La notification et un recours restent nécessaires ; un verdict annulé ne compte
pas dans les refus confirmés. Une absence de réponse admin ne vaut pas refus.

Un compte `ModerationDay` (PDA wallet × jour) porte le compteur, l'état de
bannissement et le marqueur de sanction appliquée. Un identifiant de tentative
et son commitment empêchent les doubles comptages lors de retries, signalements
multiples ou rejouements. Les refus sont enregistrés par une autorité de
modération dédiée ; une transaction de publication signée avant le bannissement
doit quand même échouer si elle est exécutée après celui-ci.

Les preuves restent privées avec une durée de conservation bornée ; pas de photo
on-chain, aucun bonus versé au détecteur ou au modérateur. L'interaction avec les
48 h de retrait reste à spécifier avant implémentation : réserve plafonnée, délai
de recours et sort des dossiers ouverts avant déblocage. Une alerte ne peut pas
geler indéfiniment la mise. Le débit reste désactivé tant que ces points ne sont
pas résolus.

### 8.7 Modèle embarqué de moins de 1 Go

Objectif : classifieur d'images spécialisé, éventuellement deux modèles pour le
contenu sexuel et la violence. Le budget est **moins de 1 Go pour l'ensemble des
poids embarqués**, avec une cible de travail de 50 à 200 Mo si la qualité le
permet. Un petit poids de fichier ne garantit ni une faible consommation RAM,
ni une faible latence, ni une bonne précision.

LiteRT ou ONNX Runtime Mobile selon le modèle et ses opérations ; conversion
et quantification à valider, puis mesure sur Seeker des deux images : temps à
froid/à chaud, pic mémoire, consommation et faux positifs. Analyser les deux
images séparément avec un prétraitement conforme au modèle. Aucun score NSFW
n'est supposé couvrir automatiquement la violence.

Une variante quantifiée du modèle NSFW Falconsai 2026 est annoncée à 87,1 Mo,
mais ses poids sont soumis à un accès contrôlé et ce format n'est pas une
intégration Android prête à l'emploi. C'est une piste, pas un modèle choisi.
La quantification peut réduire les poids, mais exige une nouvelle évaluation
des seuils après conversion.

Sources techniques :
- [Modèle NSFW et poids quantifiés Falconsai](https://huggingface.co/Falconsai/nsfw_image_detection_26/tree/main/yolo)
- [Quantification LiteRT](https://developers.google.com/edge/litert/conversion/tensorflow/quantization/post_training_quantization)

## 9. App Android

Quatre écrans.

| Écran | Contenu |
|---|---|
| Onboarding | Connexion wallet (MWA, déjà fonctionnel), faucet SKR devnet, stake initial |
| Clock In | Capture double cam → chiffrement → upload → transaction, état de progression |
| Feed | Posts du jour de la communauté, déchiffrés après obtention des clés |
| Profil | Streak, solde, historique, mise, demande de retrait et compte à rebours de 48 h |

Pendant une sortie en attente, l'écran Profil indique le solde actuel (qui peut
encore baisser en cas de jour manqué), l'heure exacte de déblocage, la possibilité
de continuer les check-ins et le bouton d'annulation. Après déblocage, il affiche
le montant final et « Retirer mes SKR » ; un keeper peut aussi finaliser la
sortie pour le propriétaire.

Le gating du feed est désormais **cryptographique vis-à-vis des non-membres** :
sans clé, un blob IPFS est inexploitable. Il reste non contraignant entre membres
(voir §8.4).

## 10. Token SKR

SKR est un token mainnet. En devnet, on crée un mint SPL de test explicitement
étiqueté comme tel. L'adresse du mint vit dans `Config`, donc le passage au vrai
mint SKR en mainnet est un changement de configuration, pas de code.

Une instruction faucet devnet-only permet à un nouvel utilisateur (et aux juges)
d'obtenir du SKR de test pour essayer l'app.

## 11. Tests

Le scaffold Anchor utilise **litesvm** — les tests tournent sans validateur local,
donc rapidement, ce qui rend le TDD praticable.

L'économie est l'endroit où les bugs coûtent cher et où les cas limites abondent ;
elle est développée en TDD :

- decay sur 1, 2, N jours manqués ; au-delà de `max_decay_days`
- refus sous `min_stake`
- reward plafonné par `reward_cap` ; par `pool_balance` ; pool vide
- double check-in le même jour (doit échouer)
- `reap` puis `check_in` le même jour (pas de double-decay)
- `reap` sur un profil à jour (doit échouer)
- `stake` après une absence : decay sur l'ancien solde, jamais sur le dépôt neuf
- demande de sortie puis finalisation avant 48 h (doit échouer) et à 48 h
- jour manqué pendant l'attente, check-in pendant l'attente, annulation avant
  déblocage, check-in après déblocage (doit échouer)
- finalisation tardive et `reap` tardif : aucun decay après le déblocage
- finalisation par un tiers : versement seulement au propriétaire
- conservation : somme des `staked` + `pool_balance` == solde du vault

Côté Android : tests du manifeste canonique et de sa signature (image, ordre des
photos, wallet ou domaine modifiés => échec), de la construction des instructions
et du chiffrement. Fixtures avec GPS/EXIF/XMP/IPTC/miniatures/commentaires :
vérifier l'absence de métadonnées sensibles dans les octets effectivement envoyés,
l'orientation correcte et l'absence d'upload si le contrôle local bloque.

Côté modération : signature et hashes invalides, autorisation expirée/rejouée,
client contournant le filtre local, un seul incident malgré plusieurs tentatives,
aucun slash aux deux premiers refus ni sur une simple alerte, avertissement au
deuxième, -10 % et blocage au troisième, remise à zéro UTC, au plus un débit
par jour, tentative en double et check-in en vol après bannissement, recours accepté,
plafond de sanction et interaction avec le retrait. Tester les faux positifs et
les pannes du modèle ; ces cas ne deviennent jamais des infractions automatiques.

Côté serveur de clés : dépôt pending authentifié avant check-in, aucune
distribution avant publication confirmée, référence/commitment correspondants,
lecture refusée sans check-in du jour, sous le minimum effectif ou en cas de
bannissement du jour ; rejet d'un
message signé périmé ou rejoué.

## 12. Hors scope v1 (assumé)

- **Résistance sybil** : rien n'empêche un utilisateur de multiplier les wallets.
  Partiellement adressé par le Seed Vault (liaison au matériel) si celui-ci est
  livré.
- **Modération entièrement autonome** : le contrôle local et la modération sont
  dans le scope (§8.6), mais aucun slash fondé uniquement sur une réponse IA.
- **Fuseaux horaires** : le jour est UTC pour tout le monde. Déterminisme on-chain
  oblige ; les utilisateurs proches de la frontière UTC ont une fenêtre décalée.
- **Déchiffrement sans tiers de confiance** : le serveur de clés reste une brique
  centralisée en v1 (§8.4).
- **Fuite par un membre légitime** : aucun mécanisme n'empêche un membre de
  redistribuer ce qu'il a déchiffré.
- **Durcissement économique** : les paramètres sont plausibles, pas simulés.

## 13. Phasage (1 à 3 semaines)

**Semaine 1 — cœur**
Programme Anchor complet en TDD (config, profil, stake, check_in, reap) ; capture
double caméra ; transaction de check-in ; écran profil avec streak et solde.
À l'issue : la boucle économique fonctionne de bout en bout sur le Seeker.

**Semaine 2 — social et confidentialité**
Chiffrement client, serveur de clés, intégration IPFS, feed global, polish UI.
L'UX pèse 25 % de la note ; cette semaine est un investissement direct sur le
score.

**Semaine 3 — différenciation et livrables**
Seed Vault, microtips, notifications. Puis vidéo de démo (3 min) et pitch deck.

Ordre choisi pour que chaque semaine laisse un état démontrable : si le temps
manque, on coupe par la fin sans casser ce qui précède.

## 14. Critères de succès de la démo

- L'app tourne sur un Seeker physique (exigence explicite du règlement).
- Un check-in complet visible de bout en bout : capture → transaction →
  explorateur → feed.
- Le decay et la redistribution démontrés concrètement (profil en retard,
  `reap`, pool qui grossit, récompense versée).
- Une sortie demandée puis débloquée après 48 h, avec pénalité seulement sur les
  jours manqués pendant l'attente.
- La confidentialité montrée par le négatif : récupérer le blob IPFS brut à
  l'écran et constater qu'il est illisible sans clé.
- La provenance vérifiable en direct par divulgation sélective (§8.3) :
  clé révélée → déchiffrement → hash → comparaison au commitment on-chain.

## 15. Questions ouvertes

### 15.1 Garde des clés de déchiffrement

**Statut : à trancher. Le schéma du §8 est retenu par défaut pour ne pas bloquer
l'implémentation, mais la décision reste ouverte.**

Ce qui est acquis et ne se rediscute pas : les images doivent être chiffrées.
Sans chiffrement, les CID inscrits on-chain rendent toutes les photos aspirables
par n'importe qui, ce qui est incompatible avec le produit.

Ce qui reste ouvert : **qui détient la capacité de déchiffrer**.

| Option | Confidentialité réelle | Coût | Défaut principal |
|---|---|---|---|
| Serveur de clés (retenu par défaut, §8) | Oui vis-à-vis des non-membres | ~1 j | L'opérateur peut tout déchiffrer |
| Déchiffrement à seuil (type Lit Protocol) | Oui, sans tiers unique | Élevé, risqué | SDK orienté JS, intégration Kotlin incertaine |
| Clé en dur dans l'app | Non (dépôt public) | Nul | Cassable en minutes ; indéfendable devant ce jury |
| Feed public assumé, sans chiffrement | Aucune | Nul | Jeu de données de visages ouvert et permanent |

Points à peser au moment de trancher :

- Le serveur de clés fait perdre l'argument « zéro serveur », mais permet un
  modèle de menace explicite — probablement mieux reçu par un jury comptant deux
  chercheurs en sécurité qu'une solution naïve présentée comme sûre.
- Le déchiffrement à seuil est le seul choix qui supprime le tiers de confiance.
  Il ne doit être tenté que hors du chemin critique : si l'intégration échoue en
  semaine 3, il faut pouvoir retomber sur le serveur de clés sans rien casser.
- L'architecture du §8 est conçue pour que ce basculement reste local : seule la
  source des clés change, ni le chiffrement client, ni le format des blobs, ni le
  modèle on-chain ne bougent.

Échéance : à décider avant le début de la semaine 2 (c'est là que le chiffrement
et le serveur de clés sont implémentés). Aucun travail de la semaine 1 n'en
dépend.

### 15.2 Paramètres économiques

Les valeurs de `min_stake`, `reward_rate_bps`, `reward_cap`, `decay_bps` et
`max_decay_days` ne sont pas arrêtées. Les deux variantes envisagées pour
`decay_bps` sont **2500 (25 %) et 5000 (50 %)** par jour manqué. Le délai de
retrait v1 est fixé à **48 h** ; choisir le taux demande encore une simulation.

| Mise initiale | 1 jour manqué à 25 % | 2 jours à 25 % | 1 jour à 50 % | 2 jours à 50 % |
|---|---:|---:|---:|---:|
| 100 SKR | 75 SKR | 56,25 SKR | 50 SKR | 25 SKR |

Ils vivent dans `Config` pour être calibrés sur devnet sans redéploiement. Avant
de recevoir du vrai SKR, les changements de taux de decay et de délai devront
être bloqués pour les positions déjà ouvertes, ou soumis à un préavis supérieur
au délai de sortie ; l'admin ne doit pas pouvoir alourdir une mise pendant que
son propriétaire attend son retrait.

À vérifier lors du calibrage : qu'un utilisateur régulier voie son solde croître
de façon perceptible sur la durée d'une démo, sans que le pool ne se vide en
quelques jours. Un membre qui continue ses check-ins durant les 48 h peut sortir
sans perte : si l'économie exige une contribution de chaque sortant, il faudra
décider explicitement d'une commission de sortie, pas compter sur le délai.

### 15.3 Faisabilité du Seed Vault

Le SDK Seed Vault est peu documenté. Prévu en semaine 3 et traité comme un bonus :
un prototype court tôt dans le projet permettra de savoir s'il faut y consacrer du
temps ou l'abandonner, avant qu'il ne coûte des jours en fin de parcours.


### 15.4 Stockage des images

Le choix IPFS chiffré reste provisoire. Alternative recommandée pendant le
brainstorming : bucket privé contenant des blobs chiffrés et backend distribuant
les clés et URL temporaires. La signature wallet, le nettoyage local et le
commitment sont indépendants du stockage. Le choix du bucket impliquera de
remplacer `cid` dans le modèle et de préciser la référence de post utilisée.

### 15.5 Paramètres et recours de modération

Acquis : nettoyage local, images signées par le wallet, filtre local avant envoi,
contrôle serveur avant publication, avertissement après le deuxième refus et
sanction de 10 % au troisième refus confirmé du jour, avec blocage publication
et feed jusqu'au prochain jour UTC. Vérification admin obligatoire des refus
comptabilisés, avant toute sanction financière. Modèle(s) embarqué(s) de moins
de 1 Go.

À préciser : définition des catégories sexe/violence et cas limites, modèle et
seuils, destination des 10 % (pool proposé), cumul avec le decay du jour banni,
délai de recours, délai de revue admin (notamment au changement de jour UTC) et
réserve bornée pendant un retrait. Le compteur faisant foi
est serveur/on-chain ; compter uniquement des rejets locaux sans permettre leur
vérification serveur ne fournit pas une base de sanction fiable. Une panne de
modération ne doit pas être assimilée à une infraction ; définir aussi le
traitement des jours où cette panne empêche les check-ins avant mise en mainnet.
