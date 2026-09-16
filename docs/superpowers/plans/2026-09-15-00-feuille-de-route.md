# Feuille de route Moment / Clock In — 3 semaines

**Spec :** [`docs/superpowers/specs/2026-09-14-clock-in-design.md`](../specs/2026-09-14-clock-in-design.md)
**Date de départ :** 2026-09-15. **Fenêtre :** 3 semaines (§13 de la spec).

Ce document n'est pas un plan d'exécution : il fixe le phasage, les décisions
arrêtées le 2026-09-15, les dépendances entre lots et les critères de sortie.
Les plans exécutables sont les quatre documents numérotés qui suivent.

---

## 1. État réel du dépôt au 2026-09-15

| Composant | Fait | Manquant |
|---|---|---|
| `program/` | Scaffold Anchor intact : `Counter`, `initialize`, `increment`. Harness litesvm opérationnel (`tests/test_initialize.rs`). | **Tout le métier** : `Config`, `Profile`, `CheckIn`, vault SPL, decay, reward, sorties, `reap`, faucet. |
| `app/` | UI Compose 4 écrans, connexion MWA (autorisation seule), capture CameraX double réelle, `PhotoSanitizer` (réencodage + purge APP/COM vérifiée), snapshot local chiffré AES-GCM/Keystore, 25 tests. | Manifeste canonique, signature MWA, construction/envoi de transaction, lecture RPC des comptes, feed on-chain. L'économie est **simulée en Kotlin** (`DemoSession`). |
| `keyserver/` | Rien. | Tout. |
| Livrables | README à jour, captures d'écran. | Vidéo 3 min, pitch, soumission dApp Store. |

Le travail `app/` et `program/` n'est **pas commité** (`git status` : `?? app/`, `?? program/`).
Premier geste du lot 1 : commiter l'existant pour que les diffs suivants soient lisibles.

## 2. Décisions arrêtées le 2026-09-15

Ces décisions ferment des questions ouvertes de la spec. Elles ne se rediscutent
pas pendant l'exécution ; elles se rediscutent en écrivant une nouvelle version
de ce document.

| # | Question | Décision | Conséquence |
|---|---|---|---|
| D1 | §15.4 Stockage | **Bucket privé chiffré**, pas IPFS. | `CheckIn.cid: String` devient `CheckIn.blob_ref: [u8; 32]` = SHA-256 du blob chiffré. Taille fixe, toujours une preuve d'intégrité, indépendant du fournisseur. |
| D2 | §15.1 Garde des clés | **Serveur de clés** (schéma §8), tiers de confiance assumé et explicite. | Le déchiffrement à seuil sort du périmètre ; l'architecture garde la source des clés derrière une interface (`KeyServerClient`). |
| D3 | §8.6 Modération | **Réduite** : filtre local + contrôle serveur + autorisation de publication. **Aucun débit on-chain.** | Pas de compte `ModerationDay`, pas d'UI admin, pas de compteur 3 refus, pas de -10 %. Le refus serveur = refus de co-signer. §8.6 reste documenté comme hors v1. |
| D4 | Autorisation de publication (§8.6) | **Co-signature de transaction** par `Config.publication_authority`. | `check_in` exige un second `Signer`. Liaison au post exacte (données d'instruction), expiration naturelle par le blockhash, rejeu bloqué par le PDA `CheckIn`. |
| D5 | Économie simulée | `DemoSession` **supprimée**. | L'état on-chain est la source de vérité unique ; l'app lit `Config`/`Profile`/`CheckIn` par RPC. |
| D6 | Jour UTC en paramètre | `day: i64` passé en argument d'instruction **et vérifié** contre `Clock`. | Nécessaire pour dériver le PDA `CheckIn` côté client. `require!(day == day_of(clock.unix_timestamp))` préserve la propriété §6 (« jamais décidé par le client »). |
| D7 | Faucet | Permissionless, une fois par profil, `config.faucet_enabled` + `config.faucet_amount`. Autorité de mint du mint de test = PDA `Config`, **mais seulement à la fin du déploiement**. | Pas de feature de compilation : le même binaire sert devnet et mainnet, l'admin coupe le faucet par configuration. |

**Correction du 2026-09-16 (D7).** Céder l'autorité de mint au PDA `Config` dès la
création du mint bloque `seed_pool` : l'admin n'a alors aucun SKR à déposer, et
aucun moyen d'en créer. L'ordre de déploiement est donc contraint — créer le mint
avec l'admin pour autorité, s'approvisionner, amorcer le pool, **puis** céder
l'autorité au programme. Après quoi seul le faucet peut créer du SKR. Les tests
litesvm ne l'avaient pas vu : ils écrivent les soldes directement dans les comptes.
`program/scripts/deploy-devnet.sh` applique cet ordre.

Restent ouvertes et **non bloquantes** pour ces 3 semaines : §15.2 (calibrage exact
des paramètres, valeurs de travail ci-dessous) et §15.3 (Seed Vault, spike timeboxé
en semaine 3).

## 3. Phasage et jalons

Chaque semaine se termine sur un état démontrable. Si le temps manque, on coupe
par la fin — aucun lot ne casse le précédent.

### Semaine 1 — cœur on-chain → [plan 01](2026-09-15-01-programme-anchor.md) puis [plan 02](2026-09-15-02-app-onchain.md)

Programme Anchor complet en TDD, puis branchement de l'app sur devnet.
En fin de semaine 1, l'autorité de publication est une **clé de développement locale**
(générée hors dépôt, injectée par `local.properties`) : elle sera remplacée par le
keyserver en semaine 2.

**Jalon J1 :** sur un Seeker ou un émulateur connecté à devnet — faucet → mise →
capture → `check_in` signé → solde, streak et compte `CheckIn` visibles dans un
explorateur. Le decay et `reap` démontrés par manipulation d'horloge en tests.

### Semaine 2 — confidentialité et social → [plan 03](2026-09-15-03-keyserver-et-feed.md)

Keyserver TypeScript (sessions wallet, séquestre des clés, bucket privé,
co-signature d'autorisation), chiffrement client du paquet, pipeline de publication
avec reprise, feed déchiffré, suppression de la clé de développement.

**Jalon J2 :** deux wallets publient le même jour ; chacun voit le post de l'autre
après son propre check-in ; le blob brut téléchargé à l'écran est illisible sans clé.

### Semaine 3 — différenciation et livrables → [plan 04](2026-09-15-04-differenciation-et-livrables.md)

Filtre local (spike modèle avec critère d'abandon), écran de divulgation sélective,
spike Seed Vault, puis vidéo de démo, pitch et soumission.

**Jalon J3 :** vidéo 3 min montrant la chaîne complète clé révélée → déchiffrement →
hash → comparaison au `commitment` on-chain, et la boucle économique.

## 4. Chemin critique et dépendances

```
Plan 01 (Anchor) ──► Plan 02 (app on-chain) ──► Plan 03 (keyserver, feed)
        │                      │                         │
        └── D4 co-signature ───┴─────────────────────────┘
                                                          └──► Plan 04 (spikes + livrables)
```

- Plan 02 dépend de l'IDL et des discriminants produits par le plan 01.
- Plan 03 dépend du manifeste canonique (plan 02, tâche 5) et du format
  d'instruction `check_in` (plan 01, tâche 6).
- Plan 04 ne bloque rien : ses deux spikes ont un critère d'abandon explicite.

## 5. Risques et parades

| Risque | Parade inscrite dans les plans |
|---|---|
| Le Seeker physique n'est pas disponible avant la fin | Tout est testé sur émulateur ; la tâche « validation Seeker » est isolée en fin de plan 02 et rejouée en plan 04. |
| Le SDK Seed Vault coûte des jours | Spike timeboxé à 1 jour, plan 04, abandon si aucun `signMessage` matériel n'est obtenu dans le temps imparti. |
| Aucun classifieur embarqué acceptable sous 1 Go | Spike timeboxé à 1 jour, plan 04. Repli : contrôle serveur seul, l'app affiche un avertissement au lieu d'un verdict local. |
| Devnet instable pendant la démo | Le RPC est paramétrable (`local.properties`) : Helius ou `api.devnet.solana.com`. Tous les tests d'économie tournent hors réseau (litesvm). |
| Le keyserver devient indisponible | Plan 03 : les retraits et l'accès wallet ne passent jamais par le keyserver (§8.4). |

## 6. Paramètres de travail (§15.2, provisoires)

Mint de test devnet, **9 décimales**. `1 SKR = 1_000_000_000` unités.

| Paramètre | Valeur de travail | Source |
|---|---|---|
| `min_stake` | 10 SKR | §15.2 à calibrer |
| `reward_rate_bps` | 100 (1 %/jour) | §6.1 |
| `reward_cap` | 1 SKR | §6.1 anti-baleine |
| `decay_bps` | 2500 (25 %) | §15.2, variante 5000 à simuler |
| `max_decay_days` | 30 | §6.1 |
| `withdrawal_delay_seconds` | 172 800 (48 h) | §6.3, **figé en v1** |
| `faucet_amount` | 100 SKR | D7 |

Ces valeurs vivent dans `Config` et se changent par `update_config` sans redéploiement.

## 7. Critères de sortie (§14)

- [ ] L'app tourne sur un Seeker physique.
- [ ] Check-in complet visible : capture → transaction → explorateur → feed.
- [ ] Decay et redistribution démontrés (profil en retard, `reap`, pool qui grossit, récompense versée).
- [ ] Sortie demandée puis débloquée à 48 h, pénalité seulement sur les jours manqués.
- [ ] Blob brut récupéré à l'écran et illisible sans clé.
- [ ] Divulgation sélective vérifiée en direct : clé → déchiffrement → hash → `commitment`.
