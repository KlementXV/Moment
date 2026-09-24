# Déploiement devnet — 2026-09-16

> **Obsolète depuis le pool journalier (2026-09-24).** `Config` et `Profile` ont
> changé de taille : ce déploiement doit être réinitialisé. Procédure en fin de page.

Déploiement de référence. Toutes ces adresses sont publiques et vérifiables.

| Rôle | Adresse |
|---|---|
| Programme `clockin` | `7TgCk9XekpU88Tiewd5VKfhmVJQyxNRR8915pzqU3rG1` |
| Mint SKR de test (9 décimales) | `2B8dXN4cgPuhh5jFM4qrJfzWt718RVpZaP7oGzmuqNPS` |
| PDA `Config` | `HvWzyGw6ADyTS74TAn7HZTEcATzRzZcao3qNrywBdZxU` |
| PDA `Vault` | `796gheKx3knzDu3qEUPLjvjCnH3KF5VyBWZJi3jYV3oj` |
| Admin | `DtdHXBnjyDCkXjponRKvWUySVx7qAXAYPCSDGiVRfHx` |
| Autorité de publication (dev) | `GjQbefZGYQDtGpBxfLoXv8tyzTHnskCERWVgyegTbT6p` |

La clé privée de l'autorité de publication vit dans `keys/`, gitignoré. Elle
sera remplacée par celle du keyserver via `set_publication_authority`.

## État vérifié après bootstrap

```
pool        500.0 SKR        min_stake   10.0 SKR
reward_cap  1.0 SKR          faucet      100.0 SKR
délai       172800 s (48 h)  reward_bps  100 (1 %/jour)
decay_bps   2500 (25 %)      max_decay   30 jours
faucet_on   true
```

**L'autorité de mint du SKR est le PDA `Config`.** Seul le faucet du programme
peut désormais en créer : `spl-token mint` échoue pour tout le monde, admin
compris. C'est la propriété qui rend le solde affiché digne de confiance.

Transactions du bootstrap :

- `initialize_config` : `45u9BHtw3FEKx4F4CBP7n8uW551p6RTmN6SPmi5qUAxbJK4eXyGDTMuSHLbPwx6AvdNerxNxPKnXfpuBLgaJs6M5`
- `seed_pool` (500 SKR) : `5nk22ZeeFQMTTf9ivsjao4QGiphX5e2qNYNwd46wkobHGxFKnPybYZALjKU61pPt69AY8PcGgQwfTET9BGaCDxiP`

## Rejouer ce déploiement

```sh
PUBLICATION_AUTHORITY=<clé publique> ./program/scripts/deploy-devnet.sh
```

Le script saute le déploiement si le programme existe déjà — redéployer coûte
la rente une seconde fois (1,59 SOL).

## Vérifier l'état on-chain

```sh
spl-token display 2B8dXN4cgPuhh5jFM4qrJfzWt718RVpZaP7oGzmuqNPS --url devnet
spl-token balance --address 796gheKx3knzDu3qEUPLjvjCnH3KF5VyBWZJi3jYV3oj --url devnet
solana account HvWzyGw6ADyTS74TAn7HZTEcATzRzZcao3qNrywBdZxU --url devnet
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

## Réinitialisation pour le pool journalier (2026-09-24)

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
crank est désactivé (log `Crank reap désactivé`).

### Vérification de bout en bout

Aucun test automatique ne couvre le crank contre une vraie chaîne : un validateur
local ne sait pas avancer d'un jour. À dérouler après la réinitialisation :

1. Jour D : deux wallets misent et publient, un troisième mise et ne publie pas.
2. Après 00:05 UTC (D+1) : les logs du keyserver montrent `crank reap sent=…`
   (règlement de l'absent) ; le `DayPool` de D porte sa pénalité.
3. Après 06:05 UTC (D+1) : nouvelle passe, les deux publieurs voient leur part
   créditée dans le profil de l'app ; « en attente » disparaît.
