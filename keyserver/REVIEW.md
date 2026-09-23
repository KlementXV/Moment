# Revue backend et déploiement — 22 septembre 2026

Périmètre : backend Rust, persistance SQLx/PostgreSQL, stockage objet compatible R2,
Dockerfile, chart Helm et intégration CloudNativePG. Les modifications Android
existantes ne font pas partie de cette revue.

## Constat corrigé

**Élevé — réplication asynchrone par défaut.** Trois instances ne garantissaient
pas la réplication d'une clé avant la réponse de cosignature. La perte du primaire
pouvait perdre une écriture validée, rendant un Moment publié indéchiffrable.
Le chart configure désormais une réplication synchrone `ANY 1`, durabilité
`required`. Il refuse une instance unique sans désactivation explicite et refuse
`synchronous_commit=off/local/remote_write` lorsque cette protection est activée.
Des régressions de rendu couvrent ces configurations. Une perte simultanée de
plusieurs nœuds reste un scénario distinct à tester ; aucun RPO universel n'est promis.

## Constats restant ouverts

Les deux constats moyens ont été corrigés après la revue :

- Quotas derrière ingress : liste CIDR explicite de proxies de confiance,
  résolution de `X-Forwarded-For` de droite à gauche, quotas distincts par client,
  protections contre les en-têtes falsifiés. Configuration exposée dans Helm.
- Feed : jusqu'à huit vérifications concurrentes par requête, résultats ordonnés,
  annulation à la fin de la requête, contrôles d'accès et d'expiration conservés.
  Une dépendance RPC totalement indisponible continue de fermer l'accès.

- **Faible — readiness limitée** (`src/store.rs`, `health`) : `SELECT 1` vérifie
  la connectivité, mais pas la capacité d'écriture ni l'état de réplication.
  La disponibilité RPC/R2 n'est pas couverte par cette sonde.

## Vérifications exécutées

- `cargo fmt --check` et Clippy avec `-D warnings` : succès.
- Tests Rust, PostgreSQL réel et moteur ONNX natif : **33 tests réussis**,
  aucun ignoré, y compris concurrence entre pools, sessions, transactions,
  chiffrement, refus d'accès et protocole R2 simulé.
- Helm : lint strict, **5 variantes valides et 11 configurations rejetées**.
- Schémas officiels CNPG 1.28 / Barman 0.15 : **5 ressources validées**.
- Image Docker construite lors de l'implémentation, retestée pendant cette revue :
  démarrage ONNX, PostgreSQL TLS `verify-full`, sondes 200, utilisateur 10001,
  racine en lecture seule et arrêt SIGTERM propre. Le Dockerfile et le code Rust
  n'ont pas changé pendant cette revue ; seul le chart et sa documentation ont changé.

## Limites de validation

Pas de déploiement sur un cluster Kubernetes réel, de panne CNPG provoquée, ni
de sauvegarde/restauration sur un vrai bucket R2. Les tests RPC et stockage objet
emploient des services simulés. La calibration du modèle pour la production,
les secrets, le domaine, la classe de stockage et les contrôles du cluster cible
restent des prérequis opérationnels. Ceci est une revue de code et de tests,
pas une certification de sécurité ni un benchmark de production.

Audit des dépendances : `cargo audit` lancé, mais téléchargement de la base
RustSec sans résultat au moment du rapport. Aucune conclusion sur l’absence
de vulnérabilités connues des dépendances ne peut donc être tirée de cette revue.

## Validation des deux corrections complémentaires

Après correction des quotas et du feed : **37 tests Rust réussis**, aucun ignoré,
formatage et Clippy sans avertissement ; Helm : **5 variantes valides et
12 configurations invalides rejetées**. Les régressions couvrent les quotas
séparés derrière un proxy, l'usurpation d'en-têtes, IPv4/IPv6, les chaînes de
proxies invalides, la concurrence plafonnée à huit, l'ordre des pages et
l'absence de réponse partielle lors d'un échec RPC.

L'image `moment-keyserver:cnpg-test` a été reconstruite avec ces corrections
(`sha256:405d8fd62f57aec771c303b771b1be6171b6844b89a39f77a7e02099ac53f3ff`).
Le test conteneur passe : ONNX natif, PostgreSQL TLS `verify-full`, sondes HTTP,
UID 10001, racine en lecture seule et arrêt SIGTERM. Aucun déploiement distant
n'a été effectué. À l'installation, `config.trustedProxyCidrs` doit contenir les
réseaux réels des ingress ; Helm refuse désormais un ingress activé sans cette
configuration. Le quota reste local à chaque replica applicatif.
