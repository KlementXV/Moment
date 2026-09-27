# Android ↔ backend : intégration et validation

État au 22 septembre 2026 : parcours implémenté, contrats testés localement.
Aucun déploiement Kubernetes ni accès à un bucket R2 réel dans cette étape.

## Configuration Android

Dans `app/local.properties`, ajouter l’origine du backend, sans chemin :

```properties
clockin.backendUrl=https://moment-devnet.example.com
clockin.network=devnet
```

Conserver les paramètres existants `clockin.rpcUrl`, `clockin.programId` et
`clockin.skrMint`. Le réseau et le programme doivent correspondre au serveur.
Le challenge est lié à cette origine, qui doit correspondre à `AUTH_ORIGIN`.
L’autorité est lue depuis `Config` on-chain et sa signature est vérifiée avant
de solliciter la signature de transaction du wallet.

`clockin.devAuthoritySecret` et `clockin.publicationAuthority` ne sont plus lus
ni inclus dans BuildConfig. Les supprimer de la configuration locale devenue
inutile ; faire tourner l’ancienne autorité avant le déploiement réel.
Les secrets R2 et la clé privée de publication restent exclusivement côté serveur.

HTTPS est obligatoire. En **debug seulement**, HTTP est accepté pour
`localhost`, `127.0.0.1` et `10.0.2.2` (émulateur). Pour un téléphone USB et un
serveur local : `adb reverse tcp:8080 tcp:8080`, puis utiliser
`http://127.0.0.1:8080` à la fois pour `clockin.backendUrl` et `AUTH_ORIGIN`.
La configuration release n’autorise pas ce transport en clair.

## Comportement

- La session backend n’est pas nécessaire au faucet, à la mise ou aux retraits.
  Elle est demandée lors de la publication ou de la lecture du feed, conservée
  en mémoire et renouvelée par signature après expiration. Un 401 l’invalide ;
  l’utilisateur relance son action. Les redirections HTTP sont refusées.
- Le manifeste signé et les JPEG nettoyés sont empaquetés, chiffrés en AES-GCM,
  puis envoyés au backend. Aucun accès direct à R2 depuis Android.
- Avant tout envoi, le paquet et sa clé sont écrits atomiquement dans
  `noBackupFilesDir`, protégés par Android Keystore et séparés par wallet/jour.
  Une erreur de sauvegarde empêche l’envoi. Une reprise conserve exactement le
  même paquet ; seul le blockhash change. Un refus définitif avant réservation
  au premier envoi permet de reprendre les photos ; un résultat réseau incertain
  conserve le paquet, pour éviter de perdre une éventuelle réservation serveur.
- Un bouton d’accueil permet de reprendre sans refaire les photos ou le manifeste.
  Le remplacement du brouillon est bloqué tant qu’une soumission est en attente.
  Si le check-in existe déjà, la confirmation serveur est idempotente et la
  reprise du feed la termine aussi après reconnexion.
- Le client refuse toute modification du message Solana par le backend ou le
  wallet. Il vérifie les deux signatures et restaure la signature serveur si
  le wallet l’efface. Une transaction confirmée avec une erreur Solana est un échec.
- Chaque page de feed est vérifiée avant affichage : hash du blob, tag GCM,
  manifeste canonique, hashes des deux photos, wallet, jour, réseau, programme
  et signature. La déconnexion et le changement de jour vident le feed en mémoire.
  Les pages suivantes restent accessibles même après une page vide avec curseur.
- Les légendes restent locales, hors manifeste et hors feed distant v1.
  Le mode illustré de démonstration reste optionnel.

## Tests reproductibles

Depuis `app/`, avec Java 17 ou le JBR d’Android Studio :

```sh
./gradlew :app:testDevnetDebugUnitTest :app:testMainnetDebugUnitTest :app:assembleDevnetDebug :app:assembleMainnetRelease :app:lintDevnetDebug --offline
```

`BackendTest` couvre le HTTP local (session, dépôt, confirmation, feed et blob),
les redirections, les sessions refusées, la liaison du challenge au déploiement,
le chiffrement, les altérations et la restauration du paquet sauvegardé.
Les tests RPC distinguent une confirmation réussie d’une transaction échouée.

Depuis `keyserver/` :

```sh
cargo test --locked --offline --test android_contract
docker compose -f compose.test.yaml up -d --wait
TEST_DATABASE_URL='postgres://moment:moment-local-test@127.0.0.1:55432/moment_test?sslmode=disable' \
  cargo test --locked --offline --lib --test api
docker compose -f compose.test.yaml down
```

`tests/fixtures/android-post-v1.json` est un vecteur public produit par Android,
avec clés de test et images synthétiques non JPEG. Kotlin vérifie sa lecture et
reconstruit sa transaction ; Rust le déchiffre, vérifie sa signature, valide la
transaction et la cosigne. Il ne sert pas à tester la modération d’images.

Ce test croisé a révélé et verrouille une correction de `web3-solana 0.3.1` :
les identifiants de programme ajoutés à la liste de comptes doivent être comptés
parmi les comptes non signataires en lecture seule. `check_in` exige l’en-tête
`[2, 1, 2]`, et non `[2, 1, 1]`.

La suite Rust utilise PostgreSQL réel, une chaîne simulée et des objets locaux /
un serveur S3 simulé. Le test ONNX natif reste séparé et nécessite `ORT_DYLIB_PATH`.
Les tests JVM de reprise n’exécutent pas Android Keystore sur un appareil.

## Recette physique restante

Prérequis : backend devnet accessible, autorité serveur configurée on-chain,
modération de développement autorisée et bucket privé configuré côté serveur.
Aucune URL de backend n’était fournie et aucun appareil ADB n’était connecté
lors de cette étape ; cette recette n’a donc pas été déclarée réussie.

1. Installer l’APK configuré sur Seeker ; connecter le wallet A, créer le profil
   et miser. Vérifier que le feed reste fermé avant publication.
2. Capturer les deux photos. Signer la connexion, le manifeste et la transaction.
   Vérifier le `CheckIn`, la confirmation serveur et l’affichage des vraies photos.
3. Sur un wallet B éligible, vérifier le refus d’accès avant sa publication ;
   publier B puis vérifier que chacun lit et déchiffre le Moment de l’autre.
4. Interrompre après le dépôt et avant la signature wallet. Fermer et relancer
   l’app : reprendre avec les mêmes références, y compris après expiration du
   blockhash ou de la session.
5. Interrompre après confirmation Solana mais avant confirmation serveur.
   Reconnecter : la reprise doit confirmer le même post sans nouvelle transaction.
6. Refuser une signature MWA, simuler une panne réseau et une réponse 401.
   Vérifier qu’aucun échec n’est affiché comme une publication terminée.
7. Tester la déconnexion, le changement de wallet, le passage de minuit UTC,
   la pagination et une photo refusée par le serveur.
8. Avec R2 réel, vérifier que les objets sont chiffrés et inaccessibles sans
   session backend. Cette étape dépend de la configuration d’infrastructure.
