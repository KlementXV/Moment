# Moment keyserver — Rust + PostgreSQL / CNPG + R2

Backend Axum pour la publication des Moments : authentification par signature
Ed25519, contrôle serveur des deux photos, séquestre chiffré des clés, stockage
privé Cloudflare R2 et co-signature limitée à `check_in`.

**État : implémentation locale, non déployée.** L’application Android utilise
le client HTTP, le paquet chiffré, la cosignature serveur et le feed distant.
Les tests HTTP du backend couvrent deux wallets, publication, confirmation et
déchiffrement. Deux tests de contrat Rust vérifient aussi un paquet et une
transaction réellement produits par Android. Le [parcours physique](../docs/android-backend-integration.md)
avec deux wallets, une URL configurée et R2 réel reste à exécuter.

Le [Dockerfile](Dockerfile) embarque le binaire, le modèle et ONNX Runtime.
Le [chart Helm complet](helm/moment-keyserver/README.md) déploie deux replicas du
backend et trois instances PostgreSQL via l’opérateur CloudNativePG existant.

## Architecture

- Rust / Axum / Tokio : HTTP ; aucune clé privée d’utilisateur n’est reçue.
- PostgreSQL via SQLx : challenges à usage unique, sessions de 15 minutes,
  réservations de posts et clés AES-256-GCM enveloppées avec une clé maître.
  Les jetons de session sont stockés uniquement sous forme de SHA-256.
  Migrations embarquées et transactions protégées contre les courses entre pods.
- `object_store` : backend local de développement ou R2 via S3, région `auto`,
  objets `moments/<sha256-hex>`, créations conditionnelles `If-None-Match: *`.
- ONNX Runtime natif, sans Python : même modèle Marqo et même prétraitement
  `pad-rgb128-bilinear-v1` que l’app. La politique et le hash du modèle sont vérifiés.
- RPC Solana : comptes `Config`, `Profile`, `CheckIn` en commitment `confirmed`,
  vérification des propriétaires, discriminants, tailles, PDA et bumps.

Le serveur peut déchiffrer les photos pour les contrôler : il est un tiers de
confiance. R2 ne reçoit que les blobs chiffrés et ne reçoit jamais leurs clés.
La clé de publication ne permet aucun retrait du vault.

## Démarrage

Prérequis : Rust récent (validé avec 1.98.1), ONNX Runtime CPU **1.23.2**,
PostgreSQL 17, le modèle livré dans `app/app/src/main/assets/moderation`, un RPC Solana et une
autorité de publication configurée dans le compte on-chain `Config`.

```sh
cd keyserver
cargo build --locked --release
mkdir -p data secrets
chmod 700 data secrets
cp .env.example .env
chmod 600 .env
```

Renseigner `.env` puis, depuis ce même dossier :

```sh
set -a
. ./.env
set +a
./target/release/moment-keyserver
```

Le fichier `.env` utilise la syntaxe du shell ; le serveur ne le charge pas
automatiquement. Les chemins relatifs sont résolus depuis le dossier courant.
Configurer `PGHOST`, `PGPORT`, `PGDATABASE`, `PGUSER`, `PGPASSWORD` et la CA dans
`PGSSLROOTCERT`. `PGSSLMODE=verify-full` est le défaut obligatoire hors loopback
devnet. `DATABASE_MAX_CONNECTIONS` vaut 10 par pod ; les migrations s’appliquent
au démarrage sous verrou PostgreSQL. Le rôle applicatif doit pouvoir créer les
tables et index dans sa base (CNPG fournit ce rôle propriétaire au bootstrap).
`KEY_ENCRYPTION_KEY` se génère
**une seule fois** avec `openssl rand -base64 32`, puis se conserve dans le
gestionnaire de secrets. Une mauvaise clé maître fait échouer la réouverture de
la base plutôt que de rendre silencieusement les anciennes clés illisibles.

Installer la bibliothèque ONNX Runtime depuis les
[releases officielles](https://github.com/microsoft/onnxruntime/releases), pour
l’architecture de l’hôte, puis renseigner le chemin absolu de `libonnxruntime.so`
(Linux) ou `libonnxruntime.dylib` (macOS) dans `ORT_DYLIB_PATH`. La bibliothèque
et ses dépendances doivent rester installées ensemble. Aucun téléchargement de
modèle ou de bibliothèque native n’a lieu au démarrage du serveur.

Le démarrage vérifie le genesis hash du réseau RPC, l’autorité on-chain et effectue une vraie inférence de
chauffe avant d’ouvrir le port HTTP. Une configuration incomplète ou une politique
non calibrée empêche le démarrage. Pour tester **sur devnet uniquement**, mettre
`ALLOW_UNCALIBRATED_MODERATION=true` ; ce réglage est refusé sur mainnet.

Pour le stockage local, remplacer `BLOB_STORE=r2` par `BLOB_STORE=local` et
renseigner `BLOB_DIR`. Cela conserve le chiffrement et tous les contrôles d’accès.

## Configuration R2

1. Créer un bucket dédié, par exemple `moment-devnet` ; garder **Public Development
   URL (`r2.dev`) désactivée** et n’attacher aucun domaine public.
2. Créer des credentials S3 R2 avec permission de lecture/écriture d’objets,
   limités à ce bucket. Le serveur ne crée ni ne configure le bucket.
3. Renseigner `R2_ENDPOINT`, `R2_BUCKET`, `R2_ACCESS_KEY_ID` et
   `R2_SECRET_ACCESS_KEY`. L’endpoint usuel est
   `https://<ACCOUNT_ID>.r2.cloudflarestorage.com` ; utiliser l’endpoint exact
   fourni par Cloudflare pour un bucket sous juridiction particulière.
4. Conserver les credentials côté serveur. L’app n’a aucun accès direct à R2 :
   les téléchargements passent par `/v1/blobs/{blobRef}` avec session et contrôle
   on-chain. Aucun lien public ou présigné ne contourne ce contrôle.

La [documentation S3 de Cloudflare](https://developers.cloudflare.com/r2/get-started/s3/)
décrit la création des credentials et l’endpoint. La région de signature est
`auto`, conformément à la
[compatibilité S3 R2](https://developers.cloudflare.com/r2/api/s3/api/).

## Contrat HTTP v1

Toutes les réponses portent `Cache-Control: no-store, private`. Les erreurs
exposent `{"error":"message en français"}`. Aucun corps, header d’autorisation,
score, image, clé ou transaction n’est journalisé. Les routes protégées exigent
`Authorization: Bearer <token>`.

Formats : adresses Solana en base58 ; `commitment` et `blobRef` en **hexadécimal
minuscule de 64 caractères** ; clés, signatures, blobs et transactions en base64
standard avec padding ; dates d’expiration en secondes Unix ; `day` entier égal
à `floor(timestampUnix / 86400)`.

### 1. Session wallet

`POST /v1/session/challenge`

```json
{"wallet":"<base58>"}
```

Réponse : `{"nonce":"<base64url>","message":"...","expiresAt":1234567890}`.
Le message lie l’origine publique `AUTH_ORIGIN`, le réseau, le programme, le wallet,
le nonce et l’expiration. Le wallet signe **les octets UTF-8 exacts de `message`**.
Le challenge dure 5 minutes. Les limites portent sur le pair HTTP et la capacité
globale, pour qu’un tiers ne puisse pas verrouiller la connexion d’un wallet
public en demandant des challenges à sa place.

`POST /v1/session/verify`

```json
{"wallet":"<base58>","nonce":"<nonce reçu>","signature":"<64 octets en base64>"}
```

Réponse : `{"token":"<base64url>","expiresAt":1234567890}`.
La consommation du challenge et la création de session forment une transaction
PostgreSQL atomique. Deux vérifications concurrentes, même sur deux pods,
ne créent pas deux sessions.

### 2. Préparation du paquet côté client

Utiliser le [manifeste canonique existant](../docs/manifest-v1.md), signé par le
wallet. Les hashes des images portent sur les JPEG nettoyés, arrière puis selfie.

Le paquet clair est la concaténation suivante, sans octets supplémentaires :

| Champ | Encodage |
|---|---|
| Domaine | ASCII `moment-post-v1` |
| Version | `u8 = 1` |
| Manifeste | longueur `u32` little-endian, puis les octets |
| Signature détachée | longueur `u32 = 64`, puis 64 octets |
| Photo arrière | longueur `u32`, puis JPEG |
| Selfie | longueur `u32`, puis JPEG |

Générer une nouvelle clé aléatoire de 32 octets par post et un nonce aléatoire de
12 octets. Chiffrer en AES-256-GCM avec AAD ASCII `moment-post-v1`.
`blob = nonce[12] || ciphertext || tag[16]`.
`blobRef = SHA256(blob)` ; `commitment = SHA256(manifeste)`.

Chaque JPEG est borné à 4 Mio et 1280 pixels par bord. EXIF, ICC, commentaires et
autres métadonnées sont refusés, y compris entre les scans JPEG ; seul un segment
JFIF sans miniature est accepté. Le serveur vérifie le paquet, les hashes et la
signature avant de contrôler réellement les deux images.

### 3. Dépôt et co-signature

`POST /v1/posts` (session requise)

```json
{
  "day": 20706,
  "commitment": "<sha256 hex du manifeste>",
  "blobRef": "<sha256 hex du blob>",
  "postKey": "<32 octets en base64>",
  "blob": "<nonce + ciphertext + tag, en base64>",
  "transaction": "<transaction Solana legacy en base64>"
}
```

La transaction contient **uniquement** `check_in` avec le vrai `blobRef`, les PDA
attendus et le wallet comme fee payer. Deux signataires : wallet en écriture,
autorité de publication en lecture seule. Config, Profile et CheckIn sont en
écriture ; programme système et programme Moment en lecture seule. Les comptes
inutiles, instructions additionnelles, formats versionnés et signatures invalides
sont refusés. La signature wallet peut être absente (64 zéros) ou déjà valide ;
elle reste intacte lors de la co-signature. Aucun transfert n’est accepté.

Le profil doit être actif, la sortie non déverrouillée et le solde après decay
au moins égal à `Config.min_stake`. Le serveur vérifie le blockhash par RPC.
Les deux images doivent avoir un score **strictement inférieur** au seuil de
revue : l’acquittement local d’une zone intermédiaire n’autorise aucun contournement
côté serveur. Les seuils actuels ne sont pas calibrés.

Réponse : `{"state":"authorized","commitment":"...","blobRef":"...","transaction":"..."}`.
Le blob et la clé enveloppée sont persistés avant le retour de la signature.
Le serveur **n’envoie pas la transaction sur Solana** : le client fait signer le
wallet, préserve la signature serveur, envoie la transaction et attend sa confirmation.

En cas de coupure, renvoyer le **même paquet, la même clé et les mêmes références**.
Une nouvelle transaction avec blockhash récent est acceptée et co-signée après
les mêmes vérifications. Un second contenu pour le même wallet/jour est refusé
avec 409. Conserver donc localement la soumission chiffrée jusqu’à confirmation ;
ne pas la rechiffrer avec un nouveau nonce pendant une reprise.

### 4. Confirmation

`POST /v1/posts/{commitment}/confirm` (session du propriétaire requise)

Le serveur lit le PDA `CheckIn`, vérifie propriétaire, jour, commitment et blobRef,
ainsi que l’intégrité du blob stocké. Réponse : `{"state":"published"}`.
Tant que cette étape n’a pas réussi, le post n’apparaît pas dans le feed et son
blob n’est pas servi. La route est idempotente et peut être rappelée après
redémarrage du client, sans nécessiter une nouvelle signature de transaction.

### 5. Feed et blobs

`GET /v1/feed?day=<jour UTC>&limit=20&after=<curseur optionnel>`

```json
{
  "items": [{
    "wallet": "<base58>",
    "commitment": "<hex>",
    "blobRef": "<hex>",
    "postKey": "<base64>",
    "blobUrl": "/v1/blobs/<hex>"
  }],
  "nextCursor": null
}
```

La page est bornée à 50 posts, ordonnée par commitment. Passer `nextCursor` comme
`after` pour la suivante ; une page peut être vide si ses posts ont disparu de la
chaîne, tout en ayant un curseur suivant.

`GET /v1/blobs/{blobRef}` renvoie le blob chiffré en `application/octet-stream`.
Les **deux routes** vérifient la session, le jour courant, le check-in du lecteur,
son solde effectif et la confirmation du post demandé. Une copie en base marquée
`published` ne suffit pas : le compte on-chain est relu avant distribution. Un
RPC indisponible ferme l’accès. L’app doit vérifier le hash, déchiffrer puis
revérifier manifeste et signature avant affichage.

Les jours antérieurs ne sont pas consultables par ces routes. Les clés déjà
reçues peuvent naturellement être conservées par leur destinataire : minuit ne
révoque pas des données déjà téléchargées.

## Exploitation

- Plusieurs replicas partagent PostgreSQL et les mêmes secrets. Le backend est
  sans disque persistant en mode R2 ; les PVC appartiennent au cluster CNPG.
  Sauvegarder PostgreSQL et la clé maître séparément : R2 seul ne restaure pas les clés.
- Mettre HTTPS devant le port local, régler `AUTH_ORIGIN` sur cette origine et
  synchroniser l’horloge de l’hôte. Ne pas activer de logs de corps/headers au proxy.
- Le serveur borne les requêtes à 12 Mio, 16 requêtes simultanées, une inférence
  simultanée et 45 secondes par requête. Le feed vérifie jusqu'à 8 publications
  simultanément par requête (au plus 128 avec l'admission HTTP), sans changer
  l'ordre de pagination. Les lectures sont annulées avec la requête.
- Le quota reste de 120 requêtes/minute par IP et **par pod**. En accès direct,
  seul le pair TCP compte. Derrière un ingress, configurer `TRUSTED_PROXY_CIDRS`
  avec ses seuls réseaux de confiance, séparés par des virgules. Le serveur lit
  `X-Forwarded-For` de droite à gauche jusqu'au premier pair non fiable ; les
  en-têtes d'un pair direct non fiable sont ignorés. Un proxy fiable sans en-tête
  valide reçoit un 400, sauf pour les sondes. Les IPv4 et IPv6 sont acceptées,
  avec au maximum 32 entrées. L'ingress doit écraser l'en-tête reçu du client ou
  y ajouter correctement l'adresse du pair ; ne pas faire confiance au réseau
  de tous les clients/pods. Ajouter une limite au proxy pour un quota partagé
  entre replicas. Le quota global des challenges
  est, lui, partagé via PostgreSQL.
- `GET /healthz` confirme que le processus HTTP tourne ; ce n’est pas une sonde
  exhaustive du RPC ou de R2. `GET /readyz` teste PostgreSQL avec un timeout de
  deux secondes. Ces sondes échappent au quota HTTP pour éviter les redémarrages
  en boucle en cas de saturation. Les erreurs de dépendances ferment l’accès aux données.
- Utiliser une autorité dédiée et effectuer sa rotation on-chain avec l’outil
  admin existant avant la bascule. Le serveur ne détient aucune clé admin.
- Aucun nettoyage automatique des posts/blobs n’est activé. Définir la durée de
  conservation et la stratégie de suppression conjointe avant une exploitation
  prolongée ; ne pas expirer les objets du jour dans une règle lifecycle R2.

## Validation

Validation locale : **33 tests Rust réussis**, dont les courses PostgreSQL entre
pools indépendants et le vrai modèle ONNX ; Clippy sans avertissement. Le chart
passe le lint strict, quatre variantes de rendu, huit configurations invalides
et la validation des ressources contre les schémas officiels CNPG/Barman.

Les tests de stockage et d’API exigent un **vrai PostgreSQL jetable** ; une URL
manquante provoque un échec explicite. Chaque test isole ses tables dans un
schéma aléatoire. Ne pas utiliser une base de production.

```sh
cargo fmt --check
cargo clippy --locked --all-targets -- -D warnings
docker compose -f compose.test.yaml up -d --wait
export TEST_DATABASE_URL='postgres://moment:moment-local-test@127.0.0.1:55432/moment_test?sslmode=disable'
cargo test --locked
# Avec ONNX Runtime installé et ORT_DYLIB_PATH renseigné :
cargo test --locked real_model_matches_android_golden -- --ignored
# Toute la suite, test natif compris :
cargo test --locked -- --include-ignored
docker compose -f compose.test.yaml down -v
```

La suite normale ne contacte ni Solana ni Cloudflare : les routes utilisent une
chaîne et une horloge injectées ; les tests RPC/S3 utilisent un serveur HTTP local.
Elle couvre les refus de signatures, le rejeu concurrent, les expirations, les
paquets altérés, la confidentialité avant confirmation, le decay, les sorties,
le changement de jour, la pagination et les reprises avec blockhash récent.
Les tests PostgreSQL utilisent plusieurs pools indépendants pour vérifier les
migrations, la consommation des nonces, les réservations concurrentes, le quota
global et le rollback des transactions.
Le test ONNX séparé charge le **vrai modèle** et compare son score au vecteur
partagé avec Android. Ces vecteurs valident l’implémentation numérique, pas la
qualité de détection ni la calibration des seuils.

Le test réel a été exécuté avec succès sous macOS avec ONNX Runtime **1.23.2**,
tolérance de score `1e-5`. Les distributions macOS 1.22.0 et 1.22.1 testées
effectuaient l’inférence mais avortaient à la fermeture du processus ; utiliser
1.23.2 pour reproduire la validation.

Une validation avec **un vrai bucket R2 et deux wallets devnet** reste nécessaire
au déploiement. Les tests locaux ne prouvent pas que les credentials, la rotation
d’autorité ou les règles du bucket d’un environnement distant sont corrects.

## Docker et Kubernetes

Depuis la racine du dépôt, avec Docker BuildKit / Buildx :

```sh
docker buildx build --load -f keyserver/Dockerfile -t moment-keyserver:0.1.0 .
python3 keyserver/tests/container_smoke.py --image moment-keyserver:0.1.0
helm lint keyserver/helm/moment-keyserver --strict
helm template moment keyserver/helm/moment-keyserver -n moment -f values-production.yaml
```

Le contexte Docker est limité par `Dockerfile.dockerignore` aux sources du serveur,
aux migrations et aux assets de modération. Aucun fichier `.env`, wallet local ou
artefact de compilation Android n’y entre. L’image multi-stage utilise Rust 1.98.1,
Debian Bookworm et ONNX Runtime 1.23.2 vérifié par SHA-256 ; branches `linux/amd64`
et `linux/arm64`. L’image finale tourne en UID/GID 10001 et supporte un système
racine en lecture seule. Elle ne contient ni compilateur ni credentials.

Le smoke test démarre PostgreSQL avec TLS, un mock RPC et le vrai backend/ONNX
dans des conteneurs jetables. Il vérifie les probes et l’arrêt SIGTERM. Il ne
contacte pas Solana ni R2. Les credentials inclus dans ce test sont fictifs.

Pour publier une image multiarchitecture dans **votre** registre :

```sh
docker buildx build --platform linux/amd64,linux/arm64 -f keyserver/Dockerfile \
  -t registry.example.com/moment-keyserver:0.1.0 --push .
```

La [documentation du chart](helm/moment-keyserver/README.md) détaille les Secrets,
les PVC CNPG, le TLS de PostgreSQL et de l’Ingress, les sauvegardes R2/Barman,
le PostgreSQL externe, la supervision et la restauration. Les CRDs et l’opérateur
CNPG doivent être installés au préalable. Aucun déploiement dans un cluster distant
n’est effectué par les tests locaux.

Cette version remplace SQLite ; elle ne supprime ni n’importe automatiquement
d’anciennes bases SQLite. Si une instance précédente contient des données utiles,
préparer leur transfert avant sa bascule.
