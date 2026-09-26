# Déploiement Kubernetes de Moment

Ce chart déploie deux instances du backend Rust, une instance du crank `reap`
quotidien (`crank.enabled`) et un cluster PostgreSQL 17
CloudNativePG de trois instances (image `17.11-minimal-trixie`, configurable par
`postgresql.imageName` ; mettre à jour les versions mineures après validation). Les photos restent dans un bucket R2 privé.
Les clés des Moments, sessions et réservations résident dans PostgreSQL ; tous
les pods utilisent la même clé maîtresse. Aucun Secret contenant des identifiants
n'est généré ni enregistré dans les valeurs Helm.

## Prérequis

- Kubernetes >= 1.28, Helm 3 ou 4, stockage persistant provisionnable. Vérifier aussi
  la matrice Kubernetes de la version CNPG choisie. Prévoir plusieurs nœuds pour
  que les réplicas apportent une tolérance aux pannes de nœud.
- Opérateur **CloudNativePG déjà installé**, version maintenue >= 1.26, avec ses
  CRDs et droits sur le namespace applicatif. Le chart n'installe ni opérateur ni
  CRDs ; aucune dépendance Helm ne modifie le cluster à votre insu.
- Image `keyserver/Dockerfile` construite et poussée dans votre registre ; définir
  `image.repository` et `image.tag` (ou `image.digest` pour une image immuable).
- Bucket R2 privé : accès public/r2.dev désactivé, jeton limité au bucket.
- Le programme Solana existe sur le réseau choisi et son autorité de publication
  correspond à la clé fournie. La configuration/modération est vérifiée au démarrage.

Pour les sauvegardes seulement : installer **Barman Cloud CNPG-I** dans le même
namespace que l'opérateur et son CRD `ObjectStore`. La procédure officielle du
plugin 0.15.0 requiert également cert-manager. Ce chart n'installe pas ces
composants. Pour les métriques PostgreSQL, `postgresql.monitoring.enabled=true`
requiert le CRD `PodMonitor` de Prometheus Operator. Aucun `enablePodMonitor`
obsolète n'est employé.

Sources officielles : [installation du plugin](https://cloudnative-pg.io/plugin-barman-cloud/docs/installation/),
[configuration des sauvegardes](https://cloudnative-pg.io/plugin-barman-cloud/docs/usage/),
[stockages S3 compatibles](https://cloudnative-pg.io/plugin-barman-cloud/docs/object_stores/).

## Secrets et installation

Créer dans le namespace `moment` un Secret `moment-keyserver-secrets` via votre
outil de gestion de secrets (External Secrets, SOPS, Vault ou kubectl). Le chart
référence simplement le Secret existant ; il n'impose aucun de ces outils.

| Clé du Secret | Contenu |
| --- | --- |
| `rpc-url` | URL HTTPS du RPC Solana, éventuel jeton inclus |
| `key-encryption-key` | 32 octets aléatoires encodés en base64 ; conserver durablement |
| `publication-authority.json` | Tableau JSON de 64 octets du keypair Solana dédié |
| `crank.json` | Keypair Solana du crank (même format), **distinct** de la clé de publication, approvisionné en SOL : il paie les `reap` et la fermeture des check-ins. Omis si `crank.enabled=false` ; peut vivre dans un autre Secret (`crank.existingSecret`) |
| `r2-access-key-id` | Identifiant S3 R2 |
| `r2-secret-access-key` | Secret S3 R2 |

Exemple à partir de fichiers protégés, **sans secrets dans l'historique shell** :

```sh
kubectl create namespace moment
kubectl -n moment create secret generic moment-keyserver-secrets \
  --from-file=rpc-url=./secrets/rpc-url \
  --from-file=key-encryption-key=./secrets/key-encryption-key \
  --from-file=publication-authority.json=./secrets/publication-authority.json \
  --from-file=crank.json=./secrets/crank.json \
  --from-file=r2-access-key-id=./secrets/r2-access-key-id \
  --from-file=r2-secret-access-key=./secrets/r2-secret-access-key
```

Les fichiers de valeurs textuelles ne doivent pas contenir de retour à la ligne
terminal. La clé de chiffrement est commune à toutes les instances : la changer
sans migration rend les clés archivées illisibles. Conserver sa copie et celle du
keypair **hors cluster**, séparément des sauvegardes de PostgreSQL.

Créer un `values-production.yaml` sans identifiants :

```yaml
image:
  repository: registry.example.com/moment-keyserver
  tag: "0.1.0"
config:
  network: devnet
  authOrigin: https://moment.example.com
  r2Endpoint: https://ACCOUNT_ID.r2.cloudflarestorage.com
  r2Bucket: moment-devnet
  # Seulement pour la politique expérimentale actuellement livrée, jamais mainnet.
  allowUncalibratedModeration: true
  # Exemple : remplacer par les adresses/réseaux réels des ingress.
  trustedProxyCidrs: ["10.1.2.3/32"]
postgresql:
  storage:
    size: 20Gi
    storageClass: your-storage-class
ingress:
  enabled: true
  className: nginx
  host: moment.example.com
  tlsSecretName: moment-tls
  annotations:
    nginx.ingress.kubernetes.io/proxy-body-size: "12m"
    nginx.ingress.kubernetes.io/proxy-read-timeout: "60"
```

```sh
helm lint ./keyserver/helm/moment-keyserver -f values-production.yaml
helm template moment ./keyserver/helm/moment-keyserver -n moment \
  -f values-production.yaml > /tmp/moment-rendered.yaml
# Vérification réelle des CRDs/admissions dans votre cluster, sans mutation :
kubectl -n moment apply --dry-run=server -f /tmp/moment-rendered.yaml
helm upgrade --install moment ./keyserver/helm/moment-keyserver -n moment \
  -f values-production.yaml --wait --timeout 15m
```

L'Ingress est désactivé par défaut. S'il est activé, un Secret TLS existant est
obligatoire ; aucun issuer cert-manager n'est supposé. Configurer la limite de
corps du contrôleur à >=12 MiB et son timeout à >=60 s. `config.authOrigin` doit
correspondre exactement à l'origine publique HTTPS (sans chemin). La limite
applicative est de 120 requêtes/minute par IP et par pod. L'activation de l'Ingress
exige `config.trustedProxyCidrs` non vide : renseigner les CIDR réels des proxies
qui joignent le backend, pas le réseau de tous les clients ou de tous les pods.
L'ingress doit écraser `X-Forwarded-For` ou ajouter correctement le pair précédent.
Le backend remonte cette chaîne de droite à gauche jusqu'au premier pair non
fiable ; un pair hors liste ne peut pas usurper son compteur avec cet en-tête.
Voir les [règles de confiance X-Forwarded-For](https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/X-Forwarded-For).
Une liste vide conserve le quota par pair TCP pour l'accès direct. Ajouter une
limitation au proxy pour partager un quota entre replicas. Les sondes ne doivent pas être mises en
cache et le port applicatif ne doit pas être exposé directement à Internet.

## Connexion PostgreSQL

Par défaut le cluster s'appelle `<release>-moment-keyserver-pg`. Le backend
utilise directement son service RW `<cluster>-rw.<namespace>.svc:5432`, avec
`PGSSLMODE=verify-full` et le certificat CA du Secret `<cluster>-ca`.
`PGUSER`/`PGPASSWORD` viennent du Secret `<cluster>-app` généré par CNPG. Pour
fournir les identifiants initiaux, définir `postgresql.existingSecret` (Secret
`kubernetes.io/basic-auth`, clés `username` et `password`, utilisateur identique
à `postgresql.owner`). La CA et le keypair sont montés en lecture seule, mode
0440, accessibles au groupe 10001. Aucun token Kubernetes n'est monté dans le pod.

`config.databaseMaxConnections` vaut 10 **par pod**, avec deux pods par défaut.
Le budget PostgreSQL de 100 connexions doit aussi couvrir les réplicas, outils et
rollouts (un pod supplémentaire), surtout si l'HPA est activé. Pas de PgBouncer
requis. Le HPA nécessite metrics-server. Le PDB `minAvailable: 1` protège contre
les interruptions volontaires ; ce n'est pas une garantie de disponibilité.

La sonde `/readyz` vérifie PostgreSQL ; `/healthz` vérifie le processus. ONNX et
la configuration Solana doivent réussir avant l'ouverture HTTP. Les probes de
startup autorisent cinq minutes. Le système de fichiers racine est en lecture
seule et `/tmp` possède un volume temporaire borné à 64 MiB.

PostgreSQL externe, toujours avec TLS vérifié :

```yaml
postgresql:
  enabled: false
externalPostgresql:
  host: postgres.example.com
  port: 5432
  database: moment
  existingSecret: moment-external-db # username/password
  caSecret: moment-external-db-ca
  caKey: ca.crt
```

L'utilisateur externe doit pouvoir créer les tables/indices dans cette base.
Le backend exécute ses migrations au démarrage. Changer `database`, `owner` ou
`existingSecret` dans `bootstrap.initdb` ne migre pas un cluster déjà initialisé.
Après rotation des secrets d'environnement, redémarrer le Deployment :
`kubectl -n moment rollout restart deployment/moment-moment-keyserver`.
Le chart calcule automatiquement un checksum des changements de ConfigMap.

## Sauvegardes PostgreSQL vers R2

Utiliser un **autre bucket** réservé aux sauvegardes et un **autre jeton** R2.
Le chart refuse de partager le bucket ou le Secret applicatif avec les sauvegardes. Le Secret `moment-postgresql-backup` contient `access-key-id` et
`secret-access-key`. Les permissions doivent permettre lecture, écriture,
listage et suppression pour la rétention. Ne pas partager les credentials de
sauvegarde avec le backend. Exemples de valeurs :

```yaml
postgresql:
  backup:
    enabled: true
    endpointURL: https://ACCOUNT_ID.r2.cloudflarestorage.com
    destinationPath: s3://moment-postgres-backups/production
    existingSecret: moment-postgresql-backup
    retentionPolicy: 30d
    schedule: "0 0 3 * * *" # six champs : secondes incluses, quotidien à 03:00
```

Le chart crée un `ObjectStore` Barman, active l'archivage WAL continu et crée un
`ScheduledBackup` utilisant `method: plugin`. Une sauvegarde initiale est demandée
immédiatement. La fenêtre de récupération est configurée sur l'`ObjectStore`,
conformément à la [rétention du plugin](https://cloudnative-pg.io/plugin-barman-cloud/docs/retention/).
Les réglages de checksum S3 et région `auto` sont fournis pour R2. Valider une
sauvegarde et une restauration dans votre environnement ; un rendu Helm ne teste
pas les credentials, le réseau ou la compatibilité effective du bucket.

```sh
kubectl -n moment get cluster,scheduledbackup,backup
kubectl -n moment describe cluster moment-moment-keyserver-pg
kubectl -n moment get objectstore moment-moment-keyserver-pg-backup
```

La sauvegarde PostgreSQL ne sauvegarde **pas les photos R2**, ni la clé maîtresse.
Préserver ces éléments indépendamment et tester leur restauration ensemble.
Les `Backup` ne dépendent pas du `ScheduledBackup` (`backupOwnerReference: none`).
Les données d'objet suivent la rétention Barman, pas la suppression des objets API.

Pour restaurer : créer un **nouveau** `Cluster` à partir d'un `ObjectStore` source,
avec `bootstrap.recovery` et `externalClusters[].plugin`, suivant la
[procédure officielle](https://cloudnative-pg.io/plugin-barman-cloud/docs/usage/#restoring-a-cluster).
Ne jamais réinitialiser le cluster existant ni réutiliser son préfixe d'archivage
comme nouvelle destination. Vérifier la restauration puis pointer une nouvelle
release applicative sur son service RW via `externalPostgresql`. Le chart ne
transforme pas automatiquement un cluster existant en cluster restauré.

## Réseau, supervision et suppression

`networkPolicy.enabled=true` sélectionne uniquement les pods du backend : entrée
8080 depuis le namespace courant par défaut, sortie DNS vers kube-dns, 5432 vers
les pods CNPG et 443 vers les endpoints HTTPS. Fournir `ingressFrom` pour votre
contrôleur d'Ingress ; `additionalEgress` pour PostgreSQL externe ou DNS local.
Exemple de sélection d'un contrôleur :

```yaml
networkPolicy:
  enabled: true
  ingressFrom:
    - namespaceSelector:
        matchLabels:
          kubernetes.io/metadata.name: ingress-nginx
      podSelector:
        matchLabels:
          app.kubernetes.io/component: controller
```

Aucune politique n'est créée pour les pods CNPG : il faut préserver les flux de
l'opérateur, réplication, métriques et plugin de sauvegarde. Sur un namespace
possédant déjà une politique deny-all, autoriser ces flux séparément. Selon le
CNI, vérifier explicitement l'accès des sondes kubelet. Le port HTTPS sortant est
large car une NetworkPolicy standard ne filtre pas par nom DNS.

`postgresql.monitoring.enabled` crée un PodMonitor ; adapter ses `labels` au
sélecteur de votre Prometheus. Les métriques/alertes du plugin de sauvegarde se
configurent indépendamment selon votre installation du plugin.

Par défaut `postgresql.keep=true` conserve le `Cluster` et l'`ObjectStore` lors
d'un `helm uninstall`. Les pods/PVC CNPG restent donc actifs et peuvent coûter de
l'argent. Le planificateur de sauvegarde Helm est supprimé, mais l'archivage WAL
reste activé sur le cluster conservé. Pour un retrait définitif : vérifier les
sauvegardes, arrêter les écritures, puis supprimer explicitement les ressources
retenues selon votre politique de conservation. Ne pas renommer la release ou
`clusterName` pour une simple mise à jour : cela créerait un autre cluster.

## Vérifications du chart

```sh
uv run --with pyyaml --with jsonschema python \
  keyserver/helm/moment-keyserver/tests/render.py
# Inclut validation des CRs contre les schémas officiels téléchargés :
uv run --with pyyaml --with jsonschema python \
  keyserver/helm/moment-keyserver/tests/render.py --crds
```

Ces tests rendent les variantes par défaut, sauvegardes, Ingress, HPA,
NetworkPolicy et PostgreSQL externe, vérifient les références TLS/Secret et
refusent des configurations invalides. La validation serveur et un déploiement
réel restent nécessaires pour les webhooks, PVC, secrets et services externes.

### Durabilité des écritures

Par défaut, `postgresql.synchronousReplication: true` configure `ANY 1` avec
`dataDurability: required` : un commit attend la persistance du WAL sur un réplica.
Cela protège notamment les clés enregistrées avant la cosignature Solana contre
la perte du seul primaire. Si aucun réplica ne répond, les écritures attendent
puis peuvent expirer ; le chart privilégie la durabilité à la disponibilité.
Le comportement est décrit dans la [documentation CNPG](https://cloudnative-pg.io/docs/1.28/replication/).

Un test local à une seule instance exige explicitement
`postgresql.synchronousReplication: false` ; ce mode ne fournit pas cette protection.
Une base externe doit configurer elle-même sa réplication synchrone. La perte
simultanée de plusieurs nœuds et les scénarios de failover doivent être validés
sur le cluster cible ; cette configuration ne remplace pas les sauvegardes.

## Crank `reap`

Un Deployment séparé (`<release>-crank`, une réplique, stratégie `Recreate`)
lance `moment-keyserver crank`. Il règle les absents à 00:05 UTC, reprend
toutes les 15 min jusqu'à la clôture du pool de la veille + 5 min, verse les
parts clôturées et ferme les check-ins de J-2 (rente rendue aux propriétaires).
Il ne lit que `NETWORK`, `PROGRAM_ID`, `rpc-url` et `crank.json` : ni base, ni
modèle, ni clé de publication. Une seule instance, jamais deux : chaque `reap`
partirait en double et le doublon paierait des frais pour échouer. Surveiller
son solde SOL ; sans fonds, les pénalités de la veille finissent au pool du jour.
