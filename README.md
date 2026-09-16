# Moment

Application Android native pour la communauté Seeker, basée sur le [plan produit](docs/superpowers/specs/2026-09-14-clock-in-design.md). Nom et direction graphique : [Moment](docs/moment-design.md).

## Version 0.3 — état on-chain sur devnet

- Interface Kotlin / Compose : graphite, ivoire, menthe et lavande ; navigation animée et retours tactiles.
- **L'économie vit on-chain.** L'app lit `Config`, `Profile` et le `CheckIn` du jour par RPC devnet : solde, série, total, demande de sortie et compte à rebours viennent des comptes Solana. L'ancienne simulation Kotlin est supprimée.
- Le solde affiché est celui **après le decay déjà dû** — ce que l'utilisateur a réellement, pas la valeur périmée stockée dans le compte. Le programme appliquera exactement le même calcul.
- Transactions construites et envoyées par l'app : `create_profile`, `faucet`, `stake`, `check_in`, `request_exit`, `cancel_exit`, `finalize_exit`. Signature par le wallet via Mobile Wallet Adapter ; aucune clé privée d'utilisateur ne transite par l'app.
- **Manifeste canonique `clockin-post-v1`** signé par le wallet en signature détachée. Son SHA-256 est le `commitment` inscrit on-chain. Format figé et documenté dans [docs/manifest-v1.md](docs/manifest-v1.md), verrouillé par un vecteur d'or calculé indépendamment.
- Capture **CameraX réelle et séquentielle** : scène arrière, puis selfie. Permissions à la demande, refus, retour depuis les réglages et erreurs caméra gérés. Aucun import galerie ni permission de localisation.
- Normalisation de l'orientation, miroir du selfie, recadrage, réduction à 1280 pixels, conversion sRGB et nouveau JPEG. Suppression des segments APP/COM, puis contrôle des octets nettoyés.
- Brouillon de capture chiffré **AES-256-GCM**, clé dans Android Keystore, fichier atomique dans `noBackupFilesDir`. Il ne contient plus aucun solde.

### Ce qui n'est pas encore là

Les photos ne sont **pas envoyées**. `blob_ref` porte provisoirement le commitment, faute de blob déposé : le chiffrement du paquet, le bucket privé et la distribution des clés arrivent avec le keyserver (plan 03).

Le feed n'affiche donc que ton propre Moment, depuis le brouillon local. Les Moments des autres exigent les clés de déchiffrement, que seul le keyserver pourra délivrer.

L'autorisation de publication est co-signée par une **clé de développement** lue dans `local.properties`, présente sur l'appareil. C'est une béquille assumée : elle disparaît quand le keyserver prend ce rôle, et `set_publication_authority` permet la rotation. Un build release sans cette clé refuse simplement de publier.

Signer localement exige Android 13 (Ed25519 dans `java.security`). Le Seeker en dispose.

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
`clockin.publicationAuthority`, `clockin.devAuthoritySecret`) et un wallet
Solana Mobile installé.

1. Installer l'APK, puis **Découvrir Moment**.
2. Dans **Moi**, connecter le wallet, recevoir 100 SKR de test puis miser 50 SKR. Chaque étape est une transaction signée par le wallet.
3. Dans **Capturer**, autoriser la caméra, capturer la scène, puis prendre le selfie.
4. Vérifier les deux images, reprendre si besoin, puis publier : le wallet signe le manifeste, puis la transaction `check_in`.
5. Vérifier le compte `CheckIn` du jour dans un explorateur devnet : son `commitment` est le SHA-256 du manifeste signé.
6. Depuis **Moi**, essayer la demande de sortie et l'annulation. Le délai est réellement de 48 h.

L’émulateur utilise ses caméras virtuelles ; rendu, latence et double caméra quasi simultanée restent à valider sur Seeker physique.

## Compiler et tester

Ouvrir `app/` dans Android Studio, SDK Android 37 et JDK compatible avec Gradle 9.7.1 (JBR Android Studio utilisé ici).

```sh
cd app
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
# Avec un appareil / émulateur connecté :
./gradlew :app:connectedDebugAndroidTest
```

APK : `app/app/build/outputs/apk/debug/app-debug.apk`. Identifiant Android conservé : `com.clockin.hackathon`, pour mettre à jour les versions précédentes.

### Vérifications

- **49 tests JVM** : Base58, discriminants Anchor recroisés avec l'IDL, encodage des instructions, décodage des comptes et solde après decay, client RPC sur pilote injecté, manifeste canonique et son vecteur d'or, assemblage et réparation de signatures, Ed25519 sur vecteurs RFC 8032, format local borné et chiffrement.
- **5 tests Android** : rotation et miroir, recadrage/résolution, GPS/identité/date, segments XMP/IPTC/commentaires, chiffrement Android Keystore.
- Android Lint sans erreur.

## Programme on-chain (devnet)

Programme Anchor `clockin` : `7TgCk9XekpU88Tiewd5VKfhmVJQyxNRR8915pzqU3rG1`.

Comptes : `Config` (PDA singleton, paramètres et pool), `Profile` (PDA par wallet),
`CheckIn` (PDA par couple wallet × jour, portant le commitment et la référence du
blob). Tout le SKR vit dans un vault SPL unique ; le decay et les récompenses sont
de la comptabilité, seuls `stake`, `seed_pool`, `faucet` et `finalize_exit` déplacent
réellement des tokens.

Instructions : `initialize_config`, `update_config`, `set_publication_authority`,
`seed_pool`, `create_profile`, `faucet`, `stake`, `check_in`, `reap`, `request_exit`,
`cancel_exit`, `finalize_exit`.

`check_in` exige une **co-signature de l'autorité de publication** : un client modifié
ne peut ni publier ni toucher de récompense sans le contrôle serveur.

Paramètres en vigueur : mise minimum 10 SKR, rendement 1 %/jour plafonné à 1 SKR,
decay 25 % par jour manqué borné à 30 jours, sortie à 48 h, faucet 100 SKR.

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

1. Déploiement devnet du programme, puis branchement de l'app : manifeste canonique,
   signature MWA, lecture des comptes par RPC et transaction `check_in` réelle.
   L'économie simulée en Kotlin disparaît à ce moment
   ([plan 02](docs/superpowers/plans/2026-09-15-02-app-onchain.md)).
2. Keyserver : chiffrement du paquet, bucket privé, contrôle avant publication,
   co-signature de l'autorisation et distribution des clés du feed
   ([plan 03](docs/superpowers/plans/2026-09-15-03-keyserver-et-feed.md)).
3. Écran de vérification par divulgation sélective, filtre local et Seed Vault
   ([plan 04](docs/superpowers/plans/2026-09-15-04-differenciation-et-livrables.md)).
4. Validation du mode double caméra sur Seeker physique.

Les paramètres économiques restent provisoires et vivent dans `Config`, donc
calibrables sur devnet sans redéploiement.

## Références techniques

- [CameraX 1.6.2](https://developer.android.com/jetpack/androidx/releases/camera)
- [Capture d’image CameraX](https://developer.android.com/media/camera/camerax/take-photo)
- [Rotation CameraX](https://developer.android.com/media/camera/camerax/orientation-rotation)

## Aperçus

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
