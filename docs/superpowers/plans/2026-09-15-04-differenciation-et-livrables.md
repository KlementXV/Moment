# Différenciation et livrables — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rendre la preuve de provenance démontrable à l'écran, tenter les deux paris de différenciation sans mettre le projet en danger, et produire les livrables du hackathon.

**Architecture:** Deux natures de tâches, à ne pas confondre. Les tâches 1, 5, 6 et 7 sont du travail déterminé : elles ont un résultat attendu et des tests. Les tâches 2 et 3 sont des **spikes** : leur résultat est une décision, pas une fonctionnalité. Chacune a une durée maximale et un critère d'abandon écrit à l'avance, parce qu'un SDK peu documenté et un classifieur embarqué sont exactement le genre de sujets qui absorbent une semaine entière en fin de projet.

**Tech Stack:** Kotlin/Compose ; LiteRT ou ONNX Runtime Mobile (à décider par la tâche 2) ; SDK Seed Vault (à évaluer par la tâche 3).

**Spec:** [`docs/superpowers/specs/2026-09-14-clock-in-design.md`](../specs/2026-09-14-clock-in-design.md) §7 (renforcement), §8.3, §8.6, §8.7, §12, §14, §15.3. Dépend des plans [01](2026-09-15-01-programme-anchor.md), [02](2026-09-15-02-app-onchain.md) et [03](2026-09-15-03-keyserver-et-feed.md).

## Global Constraints

- Un spike qui dépasse sa durée maximale est **abandonné**, pas prolongé. La décision et sa raison sont écrites dans `docs/decisions/`.
- Aucune revendication « anti-IA » nulle part : ni dans l'app, ni dans la vidéo, ni dans le pitch. Le positionnement est **provenance attestée + horodatage on-chain** (§7).
- Une alerte du classifieur local n'est jamais une infraction : elle propose de reprendre la photo, rien d'autre (§8.6, D3).
- Budget des poids embarqués : moins de 1 Go au total, cible de travail 50 à 200 Mo (§8.7).
- La vidéo montre l'app sur un **Seeker physique** (exigence du règlement, §14).
- Ce qui n'est pas construit est dit comme tel dans le pitch : sybil, modération autonome, fuseaux horaires, déchiffrement sans tiers de confiance (§12).

---

### Task 1 : Écran de vérification par divulgation sélective

C'est la démonstration la plus convaincante du projet devant un jury qui compte deux
chercheurs en sécurité : la chaîne complète clé → déchiffrement → hash → comparaison au
`commitment` on-chain, entièrement à l'écran (§8.3, §14).

**Files:**
- Create: `app/.../verify/VerificationScreen.kt`, `app/.../verify/PostVerifier.kt`
- Test: `app/app/src/test/java/com/clockin/hackathon/verify/PostVerifierTest.kt`
- Modify: `app/.../ui/MomentApp.kt`

**Interfaces:**
- Consumes : `PostPacket`, `PostManifest`, `SolanaRpc`, `ClockInAccounts`.
- Produces : `data class VerificationReport(blobRefMatches: Boolean, commitmentMatches: Boolean, signatureValid: Boolean, onChainSlot: Long?, day: Long)`, `PostVerifier.verify(blob: ByteArray, key: ByteArray, onChain: CheckInAccount?): VerificationReport`.

- [ ] **Step 1 : Écrire le test**

```kotlin
package com.clockin.hackathon.verify

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PostVerifierTest {
    @Test
    fun a_genuine_post_verifies_on_every_line() {
        val fixture = PostFixture.build()
        val report = PostVerifier.verify(fixture.blob, fixture.key, fixture.onChain)
        assertTrue(report.blobRefMatches)
        assertTrue(report.commitmentMatches)
        assertTrue(report.signatureValid)
    }

    @Test
    fun a_modified_photo_breaks_the_commitment_line_only() {
        val fixture = PostFixture.build(alterPhotoAfterSigning = true)
        val report = PostVerifier.verify(fixture.blob, fixture.key, fixture.onChain)
        assertTrue(report.blobRefMatches)
        assertFalse(report.commitmentMatches)
    }

    @Test
    fun a_commitment_that_is_not_the_one_on_chain_is_reported() {
        val fixture = PostFixture.build(onChainCommitment = ByteArray(32) { 42 })
        val report = PostVerifier.verify(fixture.blob, fixture.key, fixture.onChain)
        assertFalse(report.commitmentMatches)
    }

    @Test
    fun a_signature_from_another_wallet_is_reported_without_hiding_the_post() {
        val fixture = PostFixture.build(signWithAnotherWallet = true)
        val report = PostVerifier.verify(fixture.blob, fixture.key, fixture.onChain)
        assertFalse(report.signatureValid)
        assertTrue(report.commitmentMatches)
    }
}
```

`PostFixture` construit un post complet en mémoire : manifeste, signature Ed25519 avec
une paire de test, paquet, chiffrement, et un `CheckInAccount` cohérent.

- [ ] **Step 2 : Vérifier l'échec, implémenter, relancer**

`PostVerifier` refait, dans l'ordre et sans court-circuit, les quatre vérifications :
`sha256(blob) == checkIn.blobRef`, déchiffrement, `sha256(manifest) == checkIn.commitment`,
`verify(signature, manifest, manifest.wallet)`. Chaque ligne est rapportée séparément :
un rapport qui dit « tout est faux » n'apprend rien à personne.

Run : `cd app && ./gradlew :app:testDebugUnitTest --tests '*PostVerifierTest'`
Expected : PASS, 4 tests.

- [ ] **Step 3 : Construire l'écran**

Un écran accessible depuis un post du feed et depuis ses propres Moments :

- les deux images ;
- quatre lignes de vérification, chacune avec son état et l'octet-à-octet en hexadécimal
  tronqué (`a3f1…9b2c`) ;
- le slot on-chain et un lien vers l'explorateur ;
- un bouton « Révéler ma clé » sur ses propres posts, qui affiche la clé en base58 et
  explique en une phrase que la révéler rend ce post vérifiable par un tiers,
  définitivement.

- [ ] **Step 4 : Commit**

```bash
git add app/
git commit -m "feat(app): ecran de verification et divulgation selective"
```

---

### Task 2 : Spike classifieur embarqué — **durée maximale 1 jour**

**Question à trancher :** existe-t-il un classifieur d'images utilisable sur Seeker,
sous 1 Go de poids, avec une latence et un taux de faux positifs acceptables sur les
deux catégories retenues (contenu sexuel, violence) ?

**Critères d'acceptation, à mesurer, pas à estimer :**

| Critère | Seuil |
|---|---|
| Poids total des modèles | < 1 Go, cible 50–200 Mo |
| Latence pour les deux images, à chaud | < 1,5 s sur Seeker |
| Pic mémoire | < 400 Mo |
| Faux positifs sur 30 photos ordinaires prises pendant le spike | ≤ 1 |
| Licence | compatible avec une publication sur dApp Store |

**Critère d'abandon :** à la fin de la journée, si un seul de ces critères n'est pas
mesuré **ou** n'est pas tenu, le classifieur local est abandonné pour la v1. Le contrôle
serveur du plan 03 reste la seule barrière, et l'app affiche un rappel de règles avant
publication au lieu d'un verdict.

- [ ] **Step 1 : Choisir deux candidats et noter leur provenance**

Partir des pistes de §8.7 sans les tenir pour acquises : les poids quantifiés annoncés
à 87,1 Mo sont sous accès contrôlé et ne constituent pas une intégration Android prête
à l'emploi. Vérifier licence, accessibilité des poids et opérations supportées par le
runtime **avant** toute conversion.

- [ ] **Step 2 : Convertir et mesurer**

Conversion, quantification, puis mesure sur Seeker des deux images séparément, avec le
prétraitement exact attendu par le modèle. Mesurer à froid et à chaud. Un poids de
fichier faible ne garantit ni la RAM, ni la latence, ni la précision.

- [ ] **Step 3 : Écrire la décision**

`docs/decisions/2026-XX-XX-classifieur-local.md` : candidats, mesures, seuils tenus ou
non, décision, et ce qui serait nécessaire pour reconsidérer. Ce document a de la valeur
même — surtout — si la décision est d'abandonner.

- [ ] **Step 4 (si et seulement si les critères sont tenus) : Intégrer**

Le classifieur s'exécute sur les images **nettoyées**, avant toute sortie de l'appareil.
Une détection affiche une alerte qui propose de reprendre la photo ; l'utilisateur peut
publier quand même, et le contrôle serveur reste la barrière qui compte. Aucune image
n'est envoyée à un prestataire tiers. Tests : une image de contrôle déclenche l'alerte,
une image ordinaire ne la déclenche pas, une panne du modèle n'empêche pas de publier.

---

### Task 3 : Spike Seed Vault — **durée maximale 1 jour**

**Question à trancher :** peut-on obtenir une signature liée à l'élément sécurisé
matériel du Seeker, et l'utiliser pour signer le manifeste (§7, §15.3) ?

**Critère de réussite :** une signature produite par le Seed Vault sur les octets du
manifeste, vérifiable contre une clé publique stable, affichée dans l'app.

**Critère d'abandon :** si aucune signature n'est obtenue en une journée, le sujet est
abandonné et n'apparaît dans le pitch que comme direction future. Le SDK est peu
documenté ; c'est un pari, et il est traité comme tel.

- [ ] **Step 1 : Prototyper hors du chemin critique**

Dans une activité de démonstration séparée, jamais dans le pipeline de publication :
l'échec du spike ne doit pas pouvoir casser la boucle qui marche.

- [ ] **Step 2 : Écrire la décision**

`docs/decisions/2026-XX-XX-seed-vault.md` : ce qui a été tenté, ce qui a bloqué ou
fonctionné, et l'effort restant pour une intégration réelle.

- [ ] **Step 3 (si réussi) : Relier la preuve au matériel**

Ajouter la clé publique Seed Vault et sa signature au manifeste en **version 2** du
format — jamais en modifiant la v1, déjà figée par un vecteur d'or partagé avec le
serveur. Le serveur accepte les deux versions ; l'app affiche « signé par le matériel »
quand la v2 est utilisée.

---

### Task 4 : Rappel quotidien

Petite fonctionnalité, gros effet sur l'usage : sans notification, une app quotidienne
n'est ouverte qu'une fois. La spec assume de ne **pas** imposer de notification
aléatoire à la BeReal (§2) : c'est un rappel choisi par l'utilisateur.

- [ ] **Step 1 :** Un réglage dans l'écran Profil : heure du rappel, désactivé par défaut.
- [ ] **Step 2 :** `AlarmManager` avec une notification locale, texte sans injonction ni culpabilisation.
- [ ] **Step 3 :** Le rappel ne s'affiche pas si le check-in du jour est déjà fait.
- [ ] **Step 4 :** Demander `POST_NOTIFICATIONS` au moment de l'activation, pas au lancement.
- [ ] **Step 5 :** Commit.

---

### Task 5 : Vidéo de démo (3 minutes)

**Files:** `docs/demo-script.md`, la vidéo finale.

- [ ] **Step 1 : Écrire le script plan par plan**

Minutage proposé, à ajuster après la première prise :

| Temps | Plan | Ce qui doit être visible |
|---|---|---|
| 0:00–0:20 | Le problème | Un feed social ordinaire ; la question « qu'est-ce qui prouve que c'est réel, et qui peut le lire ? » |
| 0:20–0:50 | La boucle | Sur Seeker : capture double, signature du manifeste dans le wallet, transaction confirmée |
| 0:50–1:20 | La preuve | L'écran de vérification : clé révélée, déchiffrement, hash, `commitment` on-chain, slot, explorateur |
| 1:20–1:45 | Par le négatif | `curl` du blob brut à l'écran : illisible sans clé |
| 1:45–2:25 | L'économie | Profil régulier qui grossit, profil en retard, `reap`, pool qui monte, récompense versée |
| 2:25–2:45 | La sortie | Demande, compte à rebours de 48 h, retrait sans perte si l'on a publié |
| 2:45–3:00 | Ce qui n'est pas prouvé | La phrase exacte de §7, assumée à l'écran |

- [ ] **Step 2 : Préparer les états de démonstration**

Sur devnet, préparer à l'avance les comptes nécessaires : un profil régulier avec un
streak de plusieurs jours, un profil en retard prêt à être `reap`, un pool amorcé, une
demande de sortie proche du déblocage. Manipuler l'horloge est impossible sur devnet :
ces états se préparent en amont, sur plusieurs jours réels, ou se montrent par les
tests litesvm pour la partie temporelle.

- [ ] **Step 3 : Enregistrer sur Seeker physique**, monter, sous-titrer.

- [ ] **Step 4 : Commit du script et du lien.**

---

### Task 6 : Pitch

- [ ] **Step 1 :** Dix diapositives : problème, produit, démonstration, provenance (ce qui est prouvé et ce qui ne l'est pas), confidentialité et modèle de menace explicite, économie fermée, architecture, ce qui est hors scope et pourquoi, état d'avancement, suite.
- [ ] **Step 2 :** La diapositive « modèle de menace » présente le tableau §8.4 tel quel, opérateur du serveur de clés compris. Devant ce jury, l'honnêteté du modèle vaut mieux qu'une promesse de sécurité invérifiable.
- [ ] **Step 3 :** La diapositive « économie » montre le tableau de decay de §15.2 et dit que les paramètres sont plausibles, pas simulés.
- [ ] **Step 4 :** Relecture par une personne extérieure au projet.

---

### Task 7 : Livrables finaux et revue

- [ ] **Step 1 : README complet** — ce qui tourne, comment le compiler, comment le tester, ce qui est réel et ce qui ne l'est pas, adresses devnet, et la liste explicite du hors-scope (§12).
- [ ] **Step 2 : Passe de sécurité** sur le dépôt avant publication :

```bash
git log --all -p | grep -nE "PRIVATE KEY|secretKey|[0-9a-zA-Z]{80,}" | head -40
grep -rn "devAuthoritySecret\|PUBLICATION_AUTHORITY_KEYPAIR" --include="*.properties" --include="*.env*" . || echo "aucun secret en clair"
```

Aucune clé privée, aucun jeton, aucun fichier `local.properties` ou `.env` dans
l'historique. Si un secret a été commité, le considérer comme compromis : le faire
tourner, pas seulement le supprimer.

- [ ] **Step 3 : Vérifier les critères de sortie** de la feuille de route (§7 de ce document) un par un, en cochant ceux qui sont réellement démontrés.
- [ ] **Step 4 : Soumission** — dépôt accessible aux juges, vidéo, pitch, APK, et la mention explicite du réseau (devnet) et du mint de test.
- [ ] **Step 5 : Commit final et étiquette.**

```bash
git tag -a v1.0-hackathon -m "Moment — soumission hackathon Solana Seeker"
```

---

## Self-Review

**Couverture :** §7 renforcement Seed Vault → tâche 3 ; §8.3 divulgation sélective →
tâche 1 ; §8.6 et §8.7 filtre local → tâche 2, avec abandon possible ; §12 hors-scope →
tâches 6 et 7 ; §14 critères de démonstration → tâches 1, 5 et 7 ; §15.3 → tâche 3.

**Écarts assumés :** `tip` (§6.5) et le déchiffrement à seuil (§15.1) restent hors v1.
Les deux spikes peuvent échouer sans conséquence sur les jalons J1 et J2 : c'est la
raison pour laquelle ils sont ici et pas plus tôt.
