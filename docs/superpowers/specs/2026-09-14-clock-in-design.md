# Clock In — Design

Hackathon Solana Mobile « Clock In » (Radiants). Cible : Seeker / dApp Store Android.
Date : 2026-09-14.

## 1. Vision

Un BeReal pour la communauté Seeker, où chaque publication quotidienne est
horodatée on-chain et où ta régularité a une valeur économique en SKR.

Tu stakes du SKR pour entrer. Chaque jour où tu « clockes in », ton solde grossit
un peu. Chaque jour manqué, il fond de moitié — et ce qui fond alimente ceux qui
sont restés réguliers. L'économie est une boucle fermée : ta discipline est payée
par le relâchement des autres.

## 2. Boucle quotidienne

1. Tu ouvres l'app quand tu veux dans la journée (pas de notification aléatoire
   imposée — variante assumée par rapport à BeReal).
2. Capture double caméra avant + arrière, quasi-simultanée.
3. L'app calcule un commitment local (hash) des deux images **en clair**.
4. L'app tire une clé aléatoire, chiffre la paire d'images, et l'envoie sur IPFS.
5. Transaction `check_in` signée via Mobile Wallet Adapter : le commitment et le
   CID partent on-chain, le solde et le streak sont mis à jour.
6. La clé du post est déposée au serveur de clés (§8), qui vérifie le check-in
   on-chain avant de l'accepter.
7. Le feed de la communauté se débloque pour la journée.

## 3. Les piliers

| Pilier | Contenu |
|---|---|
| Social | Feed quotidien global de la communauté Seeker, gaté par ton propre check-in |
| Provenance | Capture verrouillée + commitment horodaté on-chain (voir §7) |
| Confidentialité | Images chiffrées ; clés séquestrées et délivrées aux membres (voir §8) |
| Économie | Stake SKR, rendement quotidien, decay -50% par jour manqué, boucle fermée |

## 4. Architecture

Quatre composants.

- **Programme Anchor** (`program/`, nom `clockin`) — source de vérité : profils,
  soldes, streaks, commitments, pool de redistribution.
- **App Android Kotlin/Compose** (`app/`) — capture, chiffrement, hash, signature
  via Mobile Wallet Adapter, lecture du feed, UI.
- **IPFS** (Pinata, free tier) — stockage des blobs **chiffrés**. Le CID reste
  une adresse de contenu, donc une preuve d'intégrité du blob transporté.
- **Serveur de clés** (`keyserver/`) — séquestre des clés de post, délivrées
  contre une preuve de check-in signée. Unique composant de confiance, isolé
  délibérément pour rester remplaçable (§8.4).

Lecture du feed : `getProgramAccounts` filtré sur le jour courant, via RPC Helius
devnet. Les blobs sont récupérés depuis IPFS par CID, les clés depuis le serveur.

## 5. Modèle de données on-chain

Décision structurante : **tout le SKR (stakes de tous les utilisateurs + pool de
redistribution) vit dans un seul vault SPL**. Le decay et les récompenses sont de
la pure comptabilité dans les comptes ; aucun transfert SPL n'a lieu. Seuls
`stake`, `unstake` et `tip` déplacent réellement des tokens. Moins cher, moins de
surface de bug.

### Comptes

**`Config`** (PDA singleton)

| Champ | Type | Rôle |
|---|---|---|
| `admin` | Pubkey | autorité de configuration |
| `skr_mint` | Pubkey | mint SKR (devnet : mint de test, swappable en mainnet) |
| `vault` | Pubkey | token account détenu par le PDA Config |
| `pool_balance` | u64 | part du vault qui appartient au pool de redistribution |
| `min_stake` | u64 | stake minimum pour pouvoir check-in |
| `reward_rate_bps` | u16 | rendement quotidien (ex. 100 = 1 % du stake) |
| `reward_cap` | u64 | plafond absolu par check-in (anti-baleine) |
| `decay_bps` | u16 | perte par jour manqué (5000 = -50 %) |
| `max_decay_days` | u8 | borne d'itération ; au-delà le solde tombe à 0 |

**`Profile`** (PDA par wallet)

| Champ | Type | Rôle |
|---|---|---|
| `owner` | Pubkey | |
| `staked` | u64 | solde unique : sert de stake ET de compteur de gains |
| `settled_day` | i64 | dernier jour dont la comptabilité est à jour |
| `last_checkin_day` | i64 | dernier jour réellement posté (affichage / streak) |
| `streak` | u32 | |
| `total_checkins` | u64 | |

Deux champs de date distincts : `settled_day` pilote le decay, `last_checkin_day`
décrit l'activité. Cette séparation est ce qui rend `reap` (§6.3) sûr contre le
double-decay.

**`CheckIn`** (PDA par couple wallet × jour)

| Champ | Type | Rôle |
|---|---|---|
| `owner` | Pubkey | |
| `day` | i64 | jour UTC |
| `commitment` | [u8; 32] | SHA-256 de (front ‖ back ‖ timestamp ‖ pubkey), calculé **sur les images en clair** |
| `cid` | String (≤64) | CID IPFS du blob **chiffré** |
| `slot` | u64 | slot d'inscription, l'ancre temporelle |
| `streak_at_checkin` | u32 | |

Le PDA par couple wallet × jour rend le double check-in structurellement
impossible : le compte existe déjà.

### Instructions

`initialize_config`, `create_profile`, `stake`, `check_in`, `reap`, `unstake`,
`faucet` (devnet uniquement, §10), et `tip` en stretch.

`stake` ne touche ni `settled_day` ni `streak` : recharger son solde ne rattrape
pas les jours manqués, le decay en attente reste dû.

## 6. Économie — règles précises

Le jour est dérivé du sysvar `Clock` (`unix_timestamp / 86_400`), jamais passé en
paramètre par le client.

### 6.1 `check_in`

1. `missed = today - settled_day - 1`
2. Si `missed > 0` : `staked` décimé de `decay_bps` par jour manqué (borné par
   `max_decay_days`, au-delà → 0). Le montant perdu est ajouté à `pool_balance`.
   `streak = 0`.
3. Si `staked < min_stake` → refus (`InsufficientStake`). Il faut recharger pour
   rejouer. C'est le mécanisme « stake pour poster, donc pour voir ».
4. `reward = min(staked × reward_rate_bps / 10_000, reward_cap, pool_balance)`
   puis `pool_balance -= reward`, `staked += reward`.
5. `streak += 1`, `settled_day = today`, `last_checkin_day = today`,
   `total_checkins += 1`.
6. Création du compte `CheckIn` avec le commitment et le CID.

Si le pool est vide, `reward = 0` : le check-in réussit quand même et le streak
progresse. L'économie ne bloque jamais la boucle sociale.

### 6.2 Amorçage

Le pool démarre vide : tant que personne n'a manqué un jour, personne ne gagne.
On l'amorce donc par un dépôt initial de l'admin au déploiement, explicitement
présenté comme tel dans le pitch (en production, ce serait le sujet du business
model).

### 6.3 `reap` — le trou de la boucle fermée

Problème identifié : si un utilisateur ne revient jamais, son solde n'est jamais
décimé et le pool ne reçoit rien. La boucle fermée s'assèche, puisqu'elle ne
s'alimente que du retour des joueurs en retard.

Solution : `reap(profile)`, instruction **permissionless**. N'importe qui peut
déclencher le decay d'un profil en retard.

- Exige `today - settled_day - 1 > 0`.
- Applique le même decay, pousse le montant perdu vers `pool_balance`.
- `settled_day = today - 1` (et non `today` : la personne peut encore poster
  aujourd'hui), `streak = 0`.

Conséquence : si elle check-in ensuite le même jour, `missed = 0` et aucun decay
supplémentaire n'est appliqué. Pas de double-decay.

Bonus au reaper (part du montant récupéré) : optionnel, phase 3.

### 6.4 `tip` (stretch)

Transfert de `staked` entre deux profils. Pure comptabilité, pas de transfert
SPL. Réaction sociale sur un post du feed.

## 7. Provenance de la capture — cadrage et menaces

Deux des juges sont chercheurs en sécurité (Voynich, a2nkf — Ethelsec).
Revendiquer « anti-IA » serait à la fois faux et contre-productif. Le
positionnement retenu est **provenance attestée + horodatage on-chain**.

### Ce qui est construit

- Capture via CameraX uniquement, double caméra quasi-simultanée.
- **Aucun chemin d'import galerie n'existe dans l'app.**
- Hash calculé localement et engagé on-chain *avant* l'upload : la chaîne est
  l'ancre temporelle, pas le serveur de stockage.
- Vérifiable par un tiers : récupérer le CID, recalculer le hash, comparer au
  `commitment`, lire le slot.

### Ce qui est prouvé

Cette paire d'images exacte existait à cet instant et a été engagée par ce wallet,
horodatée par le réseau.

### Ce qui n'est pas prouvé

Qu'un capteur a observé la réalité. Un device rooté ou une caméra virtuelle peut
injecter des frames. La double capture simultanée élève le coût de la fraude
(il faut deux flux cohérents), sans l'éliminer.

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

À la publication, côté client :

1. `commitment = SHA-256(front ‖ back ‖ timestamp ‖ pubkey)` sur les images **en
   clair** — la provenance doit attester de l'image réelle, pas du chiffré.
2. Tirage d'une clé aléatoire `post_key` (AES-256-GCM, nonce 96 bits aléatoire).
3. Chiffrement de la paire d'images, upload du blob sur IPFS → `cid`.
4. Transaction `check_in(commitment, cid)`.
5. Dépôt de `post_key` au serveur de clés, accompagné de la signature du wallet.
   Le serveur ne l'accepte qu'après avoir vérifié on-chain que le compte
   `CheckIn` correspondant existe bien avec ce `cid`.

À la lecture : le client prouve qu'il possède un `CheckIn` pour le jour demandé
(message horodaté signé via MWA), le serveur renvoie les `post_key` du jour, le
client récupère les blobs sur IPFS et déchiffre.

C'est le client qui génère la clé, pas le serveur. Ce choix élimine un blocage
d'ordonnancement : si le serveur délivrait une clé du jour contre preuve de
check-in, il faudrait déjà avoir publié pour pouvoir chiffrer.

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

Le serveur de clés est la seule brique de confiance du système. Elle est isolée
volontairement (une route, aucun stockage d'image, aucune bande passante média)
pour rester remplaçable par du déchiffrement à seuil dans une v2. C'est la
position annoncée telle quelle dans le pitch.

Le serveur est également non critique pour la chaîne : s'il tombe, les check-in
continuent de fonctionner et restent valides on-chain ; seule la lisibilité des
posts concernés est différée jusqu'au dépôt de la clé.

### 8.5 Implémentation

Node/TypeScript minimal (~150 lignes), état réduit à une table
`(day, cid) → post_key`. Déployable sur Cloudflare Workers + KV (vérification
ed25519 disponible via WebCrypto, appels RPC Solana via `fetch`).

Protections : message signé horodaté, rejeté au-delà de 2 minutes (anti-rejeu) ;
limitation de débit par pubkey.

## 9. App Android

Quatre écrans.

| Écran | Contenu |
|---|---|
| Onboarding | Connexion wallet (MWA, déjà fonctionnel), faucet SKR devnet, stake initial |
| Clock In | Capture double cam → chiffrement → upload → transaction, état de progression |
| Feed | Posts du jour de la communauté, déchiffrés après obtention des clés |
| Profil | Streak, solde, historique, stake / unstake |

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
- conservation : somme des `staked` + `pool_balance` == solde du vault

Côté Android : tests unitaires sur le calcul du commitment et la construction des
instructions, plus un aller-retour chiffrement/déchiffrement.

Côté serveur de clés : dépôt refusé si le `CheckIn` n'existe pas on-chain ;
dépôt refusé si le `cid` ne correspond pas à celui inscrit ; lecture refusée
sans check-in du jour ; rejet d'un message signé périmé (anti-rejeu).

## 12. Hors scope v1 (assumé)

- **Résistance sybil** : rien n'empêche un utilisateur de multiplier les wallets.
  Partiellement adressé par le Seed Vault (liaison au matériel) si celui-ci est
  livré.
- **Modération** : pas de signalement ni de slash sur contenu abusif.
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
`max_decay_days` ne sont pas arrêtées. `decay_bps = 5000` (-50 %) est posé par la
vision produit ; les autres sont à calibrer.

Ils vivent dans `Config` et sont modifiables par l'admin sans redéploiement : ce
choix est délibéré pour que le calibrage puisse attendre les premiers essais
réels plutôt que de bloquer l'implémentation.

À vérifier lors du calibrage : qu'un utilisateur régulier voie son solde croître
de façon perceptible sur la durée d'une démo, sans que le pool ne se vide en
quelques jours.

### 15.3 Faisabilité du Seed Vault

Le SDK Seed Vault est peu documenté. Prévu en semaine 3 et traité comme un bonus :
un prototype court tôt dans le projet permettra de savoir s'il faut y consacrer du
temps ou l'abandonner, avant qu'il ne coûte des jours en fin de parcours.
