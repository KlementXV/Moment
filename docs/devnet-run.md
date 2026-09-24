# Déploiement devnet — 2026-09-16

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
