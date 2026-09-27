# Moment

Application Android native pour la communauté Seeker, basée sur le [plan produit](docs/superpowers/specs/2026-09-14-clock-in-design.md). Nom et direction graphique : [Moment](docs/moment-design.md).

## Version 0.3 — état on-chain sur devnet

- Interface Kotlin / Compose : graphite, ivoire, menthe et lavande. Avant publication, **Home** montre un aperçu illustré flouté avec **Capturer mon Moment** au premier plan et une barre **Home + Capturer**. Après confirmation du check-in, retour sur Home déverrouillé et disparition de la barre jusqu’au prochain jour UTC. Un brouillon ne déverrouille pas Home.
- Le profil s’ouvre par l’avatar en haut à droite ; il contient la série, le wallet et les règles. Il n’a pas de barre basse : flèche et retour Android ramènent à Home.
- Échéances affichées dans le fuseau du téléphone, avec compte à rebours pendant les trois dernières heures. La fenêtre de publication du protocole reste calée sur minuit UTC. Les règles du pool journalier sont regroupées dans **Profil → Comment ça marche**.
- **L'économie vit on-chain.** L'app lit `Config`, `Profile` et le `CheckIn` du jour par RPC devnet : solde, série, total, demande de sortie et compte à rebours viennent des comptes Solana. L'ancienne simulation Kotlin est supprimée.
- Le solde affiché est celui **après le decay déjà dû** — ce que l'utilisateur a réellement, pas la valeur périmée stockée dans le compte. Le programme appliquera exactement le même calcul.
- Transactions construites et envoyées par l'app : `create_profile`, `faucet`, `stake`, `check_in`, `request_exit`, `cancel_exit`, `finalize_exit`. Signature par le wallet via Mobile Wallet Adapter ; aucune clé privée d'utilisateur ne transite par l'app.
- **Manifeste canonique `clockin-post-v1`** signé par le wallet en signature détachée. Son SHA-256 est le `commitment` inscrit on-chain. Format figé et documenté dans [docs/manifest-v1.md](docs/manifest-v1.md), verrouillé par un vecteur d'or calculé indépendamment.
- Capture **CameraX réelle et séquentielle**, dans une **sheet plein écran** : scène arrière, puis selfie, déclencheur rond et miniature de la scène. L’aperçu et la photo partagent le même cadrage. Permissions à la demande, refus, retour depuis les réglages et erreurs caméra gérés. Aucun import galerie ni permission de localisation.
- Normalisation de l'orientation, miroir du selfie, recadrage, réduction à 1280 pixels, conversion sRGB et nouveau JPEG. Suppression des segments APP/COM, puis contrôle des octets nettoyés.
- Brouillon de capture chiffré **AES-256-GCM**, clé dans Android Keystore, fichier atomique dans `noBackupFilesDir`. Il ne contient plus aucun solde.

### Intégration Android ↔ backend

L’app utilise maintenant le [backend Rust](keyserver/README.md) pour ouvrir une
session signée par le wallet, déposer le paquet AES-256-GCM, obtenir la
cosignature de `check_in`, confirmer la publication et lire le feed paginé.
Le backend seul accède à R2. `blob_ref` contient le vrai hash du paquet chiffré.
Aucune clé privée d’autorité de publication n’est embarquée, même en debug.

Une soumission est conservée atomiquement, chiffrée avec Android Keystore,
avant son premier envoi. **Reprendre la publication** réutilise les mêmes
photos, clé, nonce et références avec un blockhash récent. Si Solana a déjà
confirmé, seule la confirmation backend est reprise. Les photos du feed sont
vérifiées (hash, AES-GCM, manifeste, contexte et signature wallet) avant affichage.

Le [guide de validation](docs/android-backend-integration.md) distingue les tests
locaux du parcours physique à deux wallets. Ce dernier reste à exécuter avec
une URL de backend configurée et un appareil connecté ; le déploiement R2 réel
reste à préparer. La légende reste locale et ne fait pas partie du protocole v1.

**Profil → Démo → Afficher de faux Moments** conserve l’aperçu illustré optionnel.
Le mode normal utilise le feed distant ; le décor avant publication reste illustré.

## Déploiement devnet

Le programme tourne sur devnet. Adresses, transactions de bootstrap et procédure
de vérification : [docs/devnet-run.md](docs/devnet-run.md).

L'autorité de mint du SKR de test est le PDA `Config` : **seul le faucet du
programme peut en créer**, l'admin compris. C'est ce qui rend le solde affiché
digne de confiance.

Un test JVM décode le compte `Config` réel de devnet : si le décodeur Kotlin et
le programme Rust divergent sur un champ, il tombe.

## Essayer sur Seeker / émulateur

Prérequis : un déploiement devnet renseigné dans `app/local.properties`
(`clockin.rpcUrl`, `clockin.programId`, `clockin.skrMint`,
`clockin.backendUrl`) et un wallet
Solana Mobile installé.

L'unité du SKR vient du mint lu sur la chaîne : 9 décimales pour le mint de test
devnet, 6 pour le vrai SKR. `clockin.skrDecimals` (9 par défaut, 6 pour une build
mainnet) ne sert qu'à l'affichage avant cette lecture ; aucune mise ne part tant
que le mint n'a pas été lu.

1. Installer l'APK, puis **Découvrir Moment**.
2. Ouvrir le profil par l’avatar, recevoir 1000 SKR de test puis staker 500 SKR. Chaque étape est une transaction signée par le wallet.
3. Revenir à Home et appuyer sur **Capturer mon Moment** ou **Capturer** pour ouvrir la sheet plein écran, autoriser la caméra, capturer la scène, puis prendre le selfie.
4. Vérifier les deux images, reprendre si besoin, puis publier : le wallet signe la connexion au backend si nécessaire, le manifeste, puis la transaction `check_in` cosignée par le serveur.
5. Vérifier le compte `CheckIn` du jour dans un explorateur devnet : son `commitment` est le SHA-256 du manifeste signé.
6. Depuis le profil, essayer la demande de sortie et l'annulation. Le délai est réellement de 48 h.

Après l’onboarding, la caméra peut aussi être ouverte avant de miser : l’aperçu propose alors **Continuer vers mon profil**. Le brouillon complet est conservé à la fermeture ; la croix, le retour système et le glissement depuis l’en-tête ramènent à Home.

L’émulateur utilise ses caméras virtuelles ; rendu et latence restent à valider sur Seeker physique.

## Compiler et tester

Ouvrir `app/` dans Android Studio, SDK Android 37 et JDK compatible avec Gradle 9.7.1 (JBR Android Studio utilisé ici).

Deux builds, une par réseau, figées à la compilation :

| Variante | Nom affiché | Identifiant Android | Réseau, mint |
|---|---|---|---|
| `devnet` | Moment dev | `com.clockin.hackathon.dev` | devnet, mint de test (9 décimales) |
| `mainnet` | Moment | `com.clockin.hackathon` | mainnet, vrai SKR (6 décimales) |

Les deux s'installent côte à côte. Chaque valeur (`rpcUrl`, `programId`,
`skrMint`, `skrDecimals`, `backendUrl`) se surcharge par
`clockin.<réseau>.<nom>` dans `local.properties`, ou par la variable
d'environnement `CLOCKIN_<RÉSEAU>_<NOM>` en CI (par exemple
`CLOCKIN_MAINNET_RPC_URL`). Les anciennes clés `clockin.<nom>` restent lues pour
devnet. Une app installée avant ces variantes (identifiant
`com.clockin.hackathon`, devnet) est à désinstaller : la build mainnet la
remplacerait.

```sh
cd app
./gradlew :app:assembleDevnetDebug :app:testDevnetDebugUnitTest :app:testMainnetDebugUnitTest :app:lintDevnetDebug
# Avec un appareil / émulateur connecté :
./gradlew :app:connectedDevnetDebugAndroidTest
```

APK : `app/app/build/outputs/apk/devnet/debug/app-devnet-debug.apk` et
`app/app/build/outputs/apk/mainnet/debug/app-mainnet-debug.apk`.

### Installer sur le Seeker

Brancher le Seeker en USB, activer le débogage USB et accepter l’autorisation de l’ordinateur sur le téléphone. Depuis la racine du dépôt :

```sh
./scripts/push-seeker.sh
./scripts/push-seeker.sh --no-build
./scripts/push-seeker.sh --serial SERIAL --no-launch
```

Le script détecte le Seeker, compile l’APK debug, l’installe en conservant les données et ouvre Moment. `--no-build` réutilise l’APK existant ; `--no-launch` laisse l’application fermée. ADB est recherché dans le PATH puis dans le SDK Android ; sur macOS, le JDK d’Android Studio est utilisé si `JAVA_HOME` n’est pas défini.

### Vérifications

- **87 tests JVM** : Base58, discriminants Anchor recroisés avec l'IDL, encodage des instructions, décodage des comptes et solde après decay, client RPC sur pilote injecté, manifeste canonique et son vecteur d'or, assemblage et réparation de signatures, Ed25519 sur vecteurs RFC 8032, format local borné et chiffrement, et distribution déterministe du feed de démo.
- **21 tests Android** : rotation et miroir, recadrage/résolution, GPS/identité/date, segments XMP/IPTC/commentaires, chiffrement Android Keystore, onboarding, feed de démo et funnel quotidien (barre conditionnelle, accès au profil et retour, CTA et confidentialité de l’aperçu). Suite validée sur émulateur Android 16 ; exécution sur Seeker interrompue par le verrouillage puis la déconnexion USB.
- Android Lint sans erreur.

## Programme on-chain (devnet)

Programme Anchor `clockin` : `ANT4AF24p1io1pmdNFKd9RKMStLCFi6WGbu81QoGzqN6`.

Comptes : `Config` (PDA singleton, paramètres), `Profile` (PDA par wallet, avec ses
créances sur les pools), `DayPool` (PDA par jour : mises des publieurs et pénalités
des absents), `CheckIn` (PDA par couple wallet × jour, portant le commitment et la
référence du blob). Tout le SKR vit dans un vault SPL unique ; le decay et les parts
sont de la comptabilité, seuls `stake`, `seed_pool`, `faucet` et `finalize_exit`
déplacent réellement des tokens.

**Pool journalier** : qui ne publie pas le jour D perd 10 % de sa mise. Ces pénalités
forment le pool de D, partagé entre les publieurs de D au prorata de leur mise et
réclamable après sa clôture (D+1 06:00 UTC). Le keyserver lance `reap` à 00:05 et à
06:05 UTC pour régler les absents et verser les parts. Voir
`docs/superpowers/specs/2026-09-24-pool-journalier-design.md`.

Instructions : `initialize_config`, `update_config`, `set_publication_authority`,
`seed_pool`, `create_profile`, `faucet`, `stake`, `check_in`, `close_check_in`, `reap`,
`open_day_pool`, `roll_over_day_pool`, `request_exit`, `cancel_exit`, `finalize_exit`.

`open_day_pool` crée le pool du jour (le crank le fait à 00:05 : le premier
publieur ne paie plus sa rente). `roll_over_day_pool` reverse un pool clôturé
sans aucun publieur (amorçage ou pénalités) dans le pool du jour, au lieu de le
laisser bloqué. Le délai de clôture est d'au moins 1 h et figé au déploiement.

`close_check_in` (permissionless, à partir de J+2) ferme un `CheckIn` que plus rien
ne relit et rend sa rente (≈ 0,0013 SOL) à son propriétaire ; le crank du keyserver
le fait à chaque passage.

`check_in` exige une **co-signature de l'autorité de publication** : un client modifié
ne peut ni publier ni prendre part au pool sans le contrôle serveur.

Paramètres en vigueur : staking minimum 500 SKR, pénalité 10 % par jour manqué bornée
à 30 jours, clôture du pool à D+1 06:00 UTC, sortie à 48 h, faucet 1000 SKR.

```sh
cd program
anchor build --arch v0 && cargo test
```

**L'architecture SBPF doit rester v0** : `anchor build` cible v3 par défaut, que le
runtime embarqué dans litesvm refuse de charger. Voir
[la décision](docs/decisions/2026-09-15-architecture-sbpf.md).

58 tests : 13 unitaires sur l'économie pure, 45 d'intégration sous litesvm
(configuration, profil, faucet, mise, check-in, decay, reap, sorties, invariant de
conservation du vault).

Le développement suit les [plans](docs/superpowers/plans/2026-09-15-00-feuille-de-route.md).

## Prochains lots

1. Exécuter le [parcours Android/backend à deux wallets](docs/android-backend-integration.md)
   sur un appareil connecté et un backend devnet réel ; l’intégration est codée
   et les contrats sont testés localement.
2. Déployer le keyserver avec R2 privé, configurer l'autorité de publication
   on-chain et valider le parcours avec deux wallets devnet
   ([plan 03](docs/superpowers/plans/2026-09-15-03-keyserver-et-feed.md)).
3. Calibrer la modération, ajouter l'écran de vérification par divulgation
   sélective et explorer Seed Vault
   ([plan 04](docs/superpowers/plans/2026-09-15-04-differenciation-et-livrables.md)).
4. Validation du mode double caméra sur Seeker physique.

Les paramètres économiques restent provisoires et vivent dans `Config`, donc
calibrables sur devnet sans redéploiement.

## Références techniques

- [CameraX 1.6.2](https://developer.android.com/jetpack/androidx/releases/camera)
- [Capture d’image CameraX](https://developer.android.com/media/camera/camerax/take-photo)
- [Rotation CameraX](https://developer.android.com/media/camera/camerax/orientation-rotation)

## Aperçus

Funnel quotidien : [Home verrouillé](docs/screenshots/funnel/01-home-locked.png) · [Home après check-in avec exemples](docs/screenshots/funnel/02-home-unlocked.png). Captures des composants réels dans le harnais de test Android, avec un état de check-in fourni par le test.

Navigation et capture plein écran : [barre de navigation](docs/screenshots/capture-sheet/01-navigation.png) · [caméra](docs/screenshots/capture-sheet/02-camera.png) · [aperçu des deux photos](docs/screenshots/capture-sheet/03-review.png). Les motifs colorés sont ceux des caméras virtuelles de l’émulateur.

[Design Moment](docs/screenshots/moment/01-onboarding.png) · [Profil](docs/screenshots/moment/05-profile.png).
Les anciens aperçus restent conservés dans `docs/screenshots/` ; ils correspondent aux lots précédents.

### Caméras de l’émulateur

L’AVD du dossier était configuré sans caméra avant. Pour tester les deux prises sans modifier sa configuration sauvegardée :

```sh
emulator -avd ClockIn_Pixel7Pro_API36 -camera-front emulated -camera-back emulated -no-snapshot
```

L’app détecte les caméras manquantes et borne l’attente de l’aperçu à 12 secondes avec un message de nouvelle tentative. Le motif coloré montré dans les captures de test provient des caméras virtuelles Android, pas des anciennes illustrations intégrées à l’app.

### Validation du lot capture

Le parcours a été rejoué avec les deux caméras virtuelles : refus puis accord de permission, scène → selfie, agrandissement, reprise de la paire, sauvegarde, fermeture forcée puis restauration. Le dernier contrôle retrouve le snapshot chiffré dans `no_backup/moment-local.bin` et les deux photos dans l’app.

[Aperçu des photos](docs/screenshots/camera/04-review.png) · [Moment restauré après redémarrage](docs/screenshots/camera/06-restored.png).

25 tests réussis (20 JVM + 5 Android), compilation réussie et aucune alerte Lint. Validation sur Seeker physique encore nécessaire.

### Identité du profil

Le profil recherche automatiquement les noms `.skr` détenus par le wallet sur
Solana mainnet, indépendamment du réseau des transactions. Un pseudo local reste
prioritaire ; « Utiliser … .skr » rétablit le nom automatique. Les noms expirés
sont ignorés et, si plusieurs noms sont trouvés, le premier par ordre alphabétique
est affiché. Les domaines tokenisés et les photos AllDomains ne sont pas résolus.

La recherche utilise le RPC public mainnet par défaut. `clockin.identityRpcUrl`
dans `app/local.properties` permet de choisir un endpoint mainnet autorisant
`getProgramAccounts` et `getMultipleAccounts`. Cette URL est embarquée dans l’APK :
utiliser un proxy pour protéger une éventuelle clé privée de fournisseur RPC.
Une panne de résolution laisse le pseudo utilisable et propose de réessayer.

Intégration Marqo locale : [ONNX Runtime et prétraitement](docs/local-nsfw-integration.md).

### Analyse locale des photos

Marqo NSFW est embarqué en FP32 et analyse les deux vues pendant la prévisualisation.
Le moteur retenu est ONNX Runtime CPU / 4 threads : 329 ms par paire en médiane
sur le Seeker testé.

Les seuils de `assets/moderation/policy.json` sont expérimentaux et ne sont pas
calibrés. La vraie publication release reste désactivée tant que cette calibration
n’est pas validée. [Implémentation, mesures et outil de calibration](docs/local-nsfw-integration.md).
