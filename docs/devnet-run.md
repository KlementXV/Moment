# Déploiement devnet — 2026-09-26 (pool journalier)

Déploiement de référence. Toutes ces adresses sont publiques et vérifiables.

| Rôle | Adresse |
|---|---|
| Programme `clockin` | `ANT4AF24p1io1pmdNFKd9RKMStLCFi6WGbu81QoGzqN6` |
| Mint SKR de test (9 décimales) | `FKu7X2R2WVXDTaAQb6eE7xDtBJss9Yp3fYmBf6YyYAa5` |
| PDA `Config` | `KtzTHLLs5hoFn47WbUuttmbgj3LykhwfbtDgUfmmkLK` |
| PDA `Vault` | `DqRiergxvGcs7z51sDfjGWRqggm9eyP6xFMo2r8uekaS` |
| Admin | `DtdHXBnjyDCkXjponRKvWUySVx7qAXAYPCSDGiVRfHx` |
| Autorité de publication (dev) | `GjQbefZGYQDtGpBxfLoXv8tyzTHnskCERWVgyegTbT6p` |

La clé privée de l'autorité de publication vit dans `keys/`, gitignoré. Elle
sera remplacée par celle du keyserver via `set_publication_authority`.

L'ancien programme `7TgCk9XekpU88Tiewd5VKfhmVJQyxNRR8915pzqU3rG1` (déploiement du
2026-09-16, ancienne disposition des comptes) a été fermé le 2026-09-26 pour
récupérer sa rente (1,59 SOL). Sa clé est archivée dans `keys/`.

## État vérifié après bootstrap

```
Config      174 octets
min_stake   500 SKR          faucet      1000 SKR
retrait     172800 s (48 h)  clôture     21600 s (D+1 06:00 UTC)
decay_bps   1000 (10 %)      max_decay   30 jours
faucet_on   true
DayPool[20722] (2026-09-26)  penalties 500 SKR (amorçage), total_stake 0
```

L'amorçage est allé au pool du jour du bootstrap (jour 20722) : il reviendra
aux publieurs de ce jour-là, après la clôture du 2026-09-27 à 06:00 UTC, et
restera dans le vault si personne ne publie ce jour-là.

**L'autorité de mint du SKR est le PDA `Config`.** Seul le faucet du programme
peut désormais en créer : `spl-token mint` échoue pour tout le monde, admin
compris. C'est la propriété qui rend le solde affiché digne de confiance.

Transactions du bootstrap :

- `initialize_config` : `2iF5boHKLxJ9ZWgUFWGv3nzF9S2xjhJtKknyNAMqpS75k8kxQgNreUhMvr2aFWEGinFuRWuezxHmSntf2kUfdNgb`
- `seed_pool` (500 SKR) : `3aSh1nYQ295uGhRFUnkHMWzv5GmirXYVrhvmoPgotpCdfNGfqoADPSxfpdPCYk7LJs3JQTub1R9MBhQVqE9f736D`

## Rejouer ce déploiement

```sh
PUBLICATION_AUTHORITY=<clé publique> ./program/scripts/deploy-devnet.sh
```

Le script saute le déploiement si le programme existe déjà — redéployer coûte
la rente une seconde fois (1,96 SOL pour le programme actuel).

## Vérifier l'état on-chain

```sh
spl-token display FKu7X2R2WVXDTaAQb6eE7xDtBJss9Yp3fYmBf6YyYAa5 --url devnet
spl-token balance --address DqRiergxvGcs7z51sDfjGWRqggm9eyP6xFMo2r8uekaS --url devnet
solana account KtzTHLLs5hoFn47WbUuttmbgj3LykhwfbtDgUfmmkLK --url devnet
```

## Parcours sur le Seeker

`app/local.properties` porte les cinq paramètres (`rpcUrl`, `network`,
`programId`, `skrMint`, `publicationAuthority`, `devAuthoritySecret`) et n'est
pas commité.

```sh
cd app && ./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.clockin.hackathon/.MainActivity
```

**L'appareil doit être déverrouillé** : `adb` peut lancer l'activité derrière
l'écran de verrouillage, mais elle ne s'affiche pas et les captures ne montrent
que le verrou.

Parcours : connecter le wallet → **Moi** → recevoir 1000 SKR → staker 500 SKR →
**Capturer** → scène puis selfie → publier. Le wallet demande deux signatures :
d'abord le manifeste (signature détachée), puis la transaction `check_in`.

Vérifier ensuite le compte `CheckIn` du jour : son `commitment` doit être le
SHA-256 du manifeste signé (voir `docs/manifest-v1.md`).

## Réinitialisation pour le pool journalier (faite le 2026-09-26)

Les comptes existants ont l'ancienne disposition (`Config` 184 octets, `Profile`
95) : le nouveau programme ne peut pas les relire. On repart d'un program id neuf.

```sh
cd program
solana-keygen new -o target/deploy/clockin-keypair.json --force
anchor keys sync
anchor build --arch v0 && cargo test
PUBLICATION_AUTHORITY=<clé publique> ./scripts/deploy-devnet.sh
```

Puis reporter le nouveau program id dans `app/local.properties`
(`clockin.programId`), dans `PROGRAM_ID` du keyserver et dans ce document.

Le bootstrap écrit désormais `pool_close_delay_seconds = 21600` et
`decay_bps = 1000`, et verse l'amorçage au pool du jour UTC courant. Il ne sert
qu'aux publieurs de ce jour-là.

### Crank du keyserver

`CRANK_KEYPAIR` pointe vers un fichier de clé Solana (tableau JSON de 64 octets),
**distinct** de `PUBLICATION_AUTHORITY_KEYPAIR`, approvisionné en SOL : il paie
les frais de `reap` et la rente des `DayPool` qu'il crée. Sans cette variable, le
crank est désactivé (log `Crank reap désactivé`). Il passe à 00:05 UTC puis
toutes les 15 min jusqu'à la clôture de la veille + 5 min : un passage raté est
repris tant que le pool de la veille est ouvert, sinon ses pénalités iraient au
pool du jour. À chaque passage, il ferme aussi les `CheckIn` de J-2 et avant
(`close_check_in`, 8 par transaction) : leur rente revient à leurs propriétaires.

### Vérification de bout en bout

Aucun test automatique ne couvre le crank contre une vraie chaîne : un validateur
local ne sait pas avancer d'un jour. À dérouler après la réinitialisation :

1. Jour D : deux wallets misent et publient, un troisième mise et ne publie pas.
2. Après 00:05 UTC (D+1) : les logs du keyserver montrent `crank reap sent=…`
   (règlement de l'absent) ; le `DayPool` de D porte sa pénalité.
3. Après 06:05 UTC (D+1) : nouvelle passe, les deux publieurs voient leur part
   créditée dans le profil de l'app ; « en attente » disparaît.
