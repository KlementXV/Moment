# Moment

Application Android native pour la communauté Seeker, basée sur le [plan produit](docs/superpowers/specs/2026-09-14-clock-in-design.md). Nom et direction graphique : [Moment](docs/moment-design.md).

## Version 0.2 — capture réelle, sauvegarde locale

- Interface Kotlin / Compose : graphite, ivoire, menthe et lavande ; navigation animée et retours tactiles.
- Connexion Mobile Wallet Adapter : autorisation wallet réelle, indépendante des soldes fictifs.
- Capture **CameraX réelle et séquentielle** : scène arrière, puis selfie. Permissions à la demande, refus, retour depuis les réglages et erreurs caméra gérés. Aucun import galerie ni permission de localisation.
- Aperçu des deux photos, agrandissement du selfie au toucher, reprise de la paire avant validation.
- Normalisation de l’orientation, miroir du selfie, recadrage CameraX, réduction à 1280 pixels maximum, conversion sRGB et nouveau JPEG. Suppression des segments APP/COM (dont les profils ajoutés par l’encodeur Android), puis contrôle des octets nettoyés.
- Les buffers bruts restent en mémoire et sont fermés après traitement. Aucun original ne passe par un fichier ou par le réseau.
- Snapshot local **AES-256-GCM**, clé dans Android Keystore, fichier atomique dans `noBackupFilesDir`. Les deux photos, leurs SHA-256 et l’état économique fictif sont enregistrés ensemble.
- Dernier Moment et position démo restaurés après fermeture de l’app. Feed du jour verrouillé avant son propre enregistrement ; le post de `maya.skr` reste un exemple fictif illustré.
- Mise fictive, série UTC, decay, récompense bornée au pool, demande de sortie à 48 h et annulation.

### Ce qui reste local

Les photos ne sont **pas envoyées** et aucun token n’est transféré. « Enregistrer mon Moment · local » sauvegarde sur cet appareil et avance la simulation économique. Ce n’est pas une publication on-chain.

Le chiffrement local protège le fichier au repos ; l’app peut déchiffrer ce fichier avec sa clé Keystore. Il ne constitue pas encore le chiffrement par publication avec séquestre des clés décrit dans le plan. Les SHA-256 locaux servent au contrôle d’intégrité ; ils ne remplacent ni manifeste canonique ni signature wallet.

Un seul post local est conservé : le prochain enregistrement quotidien le remplace. Une paire complète en attente de validation survit à la rotation via le ViewModel, mais pas à l’arrêt du processus. Une capture incomplète est à recommencer après recréation de l’écran. Les photos déjà enregistrées et la position restent persistantes.

En cas de fichier corrompu ou de clé inaccessible, la lecture échoue avec un message et un bouton de nouvelle tentative ; l’app n’écrase pas automatiquement le fichier. Désinstaller l’app ou effacer ses données Android efface la démo et sa clé. Aucun compte blockchain n’est affecté.

## Essayer sur Seeker / émulateur

1. Installer l’APK, puis **Découvrir Moment**.
2. Dans **Moi**, recevoir 100 SKR fictifs et miser 50 SKR.
3. Dans **Capturer**, autoriser la caméra, capturer la scène, puis prendre le selfie.
4. Vérifier les deux images (toucher le petit cadre pour l’agrandir), reprendre si besoin, puis **Enregistrer mon Moment · local**.
5. Vérifier le feed et fermer / relancer l’app : le Moment et le solde sont retrouvés.
6. Depuis **Moi**, essayer la demande de sortie et l’annulation. Le délai est réellement de 48 h.

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

- **20 tests JVM** : économie, sortie, UTC, Base58 ; format local borné, hashes, données tronquées, chiffrement aléatoire, mauvaise clé et altération du ciphertext/nonce.
- **5 tests Android** : rotation et miroir, recadrage/résolution, GPS/identité/date, segments XMP/IPTC/commentaires, chiffrement Android Keystore.
- Android Lint et contrôle du parcours sur Pixel 7 Pro / API 36.

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
