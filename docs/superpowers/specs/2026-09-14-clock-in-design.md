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
3. L'app calcule un commitment local (hash) des deux images.
4. Transaction `check_in` signée via Mobile Wallet Adapter : le commitment part
   on-chain, le solde et le streak sont mis à jour.
5. Upload des images sur IPFS.
6. Le feed de la communauté se débloque pour la journée.

## 3. Les trois piliers

| Pilier | Contenu |
|---|---|
| Social | Feed quotidien global de la communauté Seeker, gaté par ton propre check-in |
| Provenance | Capture verrouillée + commitment horodaté on-chain (voir §7) |
| Économie | Stake SKR, rendement quotidien, decay -50% par jour manqué, boucle fermée |

## 4. Architecture

Trois composants, aucun backend.

- **Programme Anchor** (`program/`, nom `clockin`) — source de vérité : profils,
  soldes, streaks, commitments, pool de redistribution.
- **App Android Kotlin/Compose** (`app/`) — capture, hash, signature via Mobile
  Wallet Adapter, lecture du feed, UI.
- **IPFS** (Pinata, free tier) — stockage des images. Le CID est lui-même une
  adresse de contenu, donc il double comme preuve d'intégrité.

Lecture du feed : `getProgramAccounts` filtré sur le jour courant, via RPC Helius
devnet. Les images sont récupérées depuis IPFS par CID.

L'absence de backend est un choix de design revendiqué dans le pitch : client +
chaîne + stockage décentralisé, rien d'autre.

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
| `commitment` | [u8; 32] | SHA-256 de (front ‖ back ‖ timestamp ‖ pubkey) |
| `cid` | String (≤64) | CID IPFS pour la récupération des images |
| `slot` | u64 | slot d'inscription, l'ancre temporelle |
| `streak_at_checkin` | u32 | |

Le PDA par couple wallet × jour rend le double check-in structurellement
impossible : le compte existe déjà.

### Instructions

`initialize_config`, `create_profile`, `stake`, `check_in`, `reap`, `unstake`,
`faucet` (devnet uniquement, §9), et `tip` en stretch.

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

## 8. App Android

Quatre écrans.

| Écran | Contenu |
|---|---|
| Onboarding | Connexion wallet (MWA, déjà fonctionnel), faucet SKR devnet, stake initial |
| Clock In | Capture double cam → hash → transaction, état de progression |
| Feed | Posts du jour de la communauté, gaté par ton propre check-in |
| Profil | Streak, solde, historique, stake / unstake |

Le gating du feed est **UX, pas cryptographique** : les images IPFS restent
publiquement accessibles. Un gating réel demanderait chiffrement et distribution
de clés — hors scope v1, à assumer si la question est posée.

## 9. Token SKR

SKR est un token mainnet. En devnet, on crée un mint SPL de test explicitement
étiqueté comme tel. L'adresse du mint vit dans `Config`, donc le passage au vrai
mint SKR en mainnet est un changement de configuration, pas de code.

Une instruction faucet devnet-only permet à un nouvel utilisateur (et aux juges)
d'obtenir du SKR de test pour essayer l'app.

## 10. Tests

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
instructions.

## 11. Hors scope v1 (assumé)

- **Résistance sybil** : rien n'empêche un utilisateur de multiplier les wallets.
  Partiellement adressé par le Seed Vault (liaison au matériel) si celui-ci est
  livré.
- **Modération** : pas de signalement ni de slash sur contenu abusif.
- **Fuseaux horaires** : le jour est UTC pour tout le monde. Déterminisme on-chain
  oblige ; les utilisateurs proches de la frontière UTC ont une fenêtre décalée.
- **Gating cryptographique du feed** (voir §8).
- **Durcissement économique** : les paramètres sont plausibles, pas simulés.

## 12. Phasage (1 à 3 semaines)

**Semaine 1 — cœur**
Programme Anchor complet en TDD (config, profil, stake, check_in, reap) ; capture
double caméra ; transaction de check-in ; écran profil avec streak et solde.
À l'issue : la boucle économique fonctionne de bout en bout sur le Seeker.

**Semaine 2 — social**
Feed global, intégration IPFS, polish UI. L'UX pèse 25 % de la note ; cette
semaine est un investissement direct sur le score.

**Semaine 3 — différenciation et livrables**
Seed Vault, microtips, notifications. Puis vidéo de démo (3 min) et pitch deck.

Ordre choisi pour que chaque semaine laisse un état démontrable : si le temps
manque, on coupe par la fin sans casser ce qui précède.

## 13. Critères de succès de la démo

- L'app tourne sur un Seeker physique (exigence explicite du règlement).
- Un check-in complet visible de bout en bout : capture → transaction →
  explorateur → feed.
- Le decay et la redistribution démontrés concrètement (profil en retard,
  `reap`, pool qui grossit, récompense versée).
- La provenance vérifiable en direct : CID → hash → comparaison au commitment
  on-chain.
