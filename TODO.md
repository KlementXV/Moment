# Tâches restantes

## 3. Préparer la configuration réelle

- [ ] Mint SKR mainnet : `SKRbvo6Gf7GondiT3BbTfuRDPqLWei4j2Qy2NPGZhW3` (`clockin.skrMint` côté app, `skr_mint` de la Config)
- [ ] Le vrai SKR a **6 décimales**, pas 9 comme le mint de test : `SKR` dans `ClockInModel.kt` et le script de bootstrap supposent 9. Rendre l'unité dépendante du réseau (ou de `decimals` lu sur le mint) avant mainnet
- [ ] Config mainnet : `faucet_enabled = false`, `min_stake` = 500 SKR en unités à 6 décimales (`500_000_000`)
- [ ] Registre Docker pour l'image du keyserver
- [ ] Domaine et TLS (doit correspondre à `AUTH_ORIGIN` et à `clockin.backendUrl`)
- [ ] Secrets : clé d'autorité de publication, `KEY_ENCRYPTION_KEY`, identifiants PostgreSQL et R2
- [ ] Buckets R2 privés et clés d'accès (`BLOB_STORE=r2`, `R2_ENDPOINT`, `R2_BUCKET`, `R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY`)
- [ ] Classe de stockage Kubernetes
- [ ] Opérateur CNPG (PostgreSQL)

## 4. Déployer et tester sur Kubernetes

- [ ] Déployer avec le chart `keyserver/helm/moment-keyserver`
- [ ] Publication complète de bout en bout (Android → keyserver → R2 → Solana → feed)
- [ ] Bascule PostgreSQL après panne
- [ ] Sauvegarde et restauration R2
- [ ] Vérifier que les objets R2 sont chiffrés et inaccessibles sans session backend (étape 8 de `docs/android-backend-integration.md`)

## 5. Valider la production

- [ ] Calibrer la modération (retirer `ALLOW_UNCALIBRATED_MODERATION`)
- [ ] Auditer les dépendances (Cargo, Gradle)
- [ ] Tests de charge
- [ ] Alertes (métriques via le PodMonitor)
