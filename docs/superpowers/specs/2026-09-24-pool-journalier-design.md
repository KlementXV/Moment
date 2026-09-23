# Pool journalier — design

Date : 2026-09-24
Remplace la redistribution continue (§6.1 de `2026-09-14-clock-in-design.md`).

## Objectif

Qui ne publie pas son moment le jour J perd 10 % de sa mise. Ces pénalités
forment le pool du jour J, redistribué aux publieurs de J **au prorata de leur
mise**, et visible dans leur solde le lendemain matin.

## Règles

- Pénalité : `decay_bps = 1000` (10 %) par jour manqué, composée, bornée par
  `max_decay_days` (inchangé).
- Pool du jour D : somme des pénalités des absents de D.
- Gain d'un publieur de D : `penalties[D] × mise_au_check_in / total_stake[D]`,
  arrondi à l'inférieur. La poussière d'arrondi reste dans le vault, non attribuée.
- Le pool de D est **clôturé** à `(D+1) × 86400 + pool_close_delay_seconds`
  (défaut 6 h → D+1 06:00 UTC). Avant clôture : aucune réclamation possible.
  Après clôture : plus aucune pénalité n'y entre.
- Pénalité réglée après la clôture de D → versée au pool du jour courant.
- Pénalité d'un jour D sans aucun publieur (`total_stake[D] == 0`) → versée au
  pool du jour courant. Comme on ne publie pour D que pendant D, ce total est
  définitif dès que D est passé : pas de report à gérer.
- Le jour courant n'est jamais pénalisable (`settle_bound = today - 1`,
  inchangé). `reap` reste permissionless et ne rapporte rien à l'appelant.

## État on-chain

### `DayPool` (nouveau PDA, graines `["day_pool", day.to_le_bytes()]`)

| Champ | Rôle |
|---|---|
| `day: i64` | jour UTC |
| `penalties: u64` | SKR entrés dans ce pool |
| `total_stake: u64` | somme des mises des publieurs de ce jour |
| `winners_count: u32` | affichage |
| `bump: u8` | |

Créé en `init_if_needed` par le premier `check_in` du jour ou par le premier
`reap` qui y verse (payeur : signataire de la transaction).

### `Profile` (champs ajoutés) — créances en attente

Cas : publié lundi, publie mardi à 03:00, pool de lundi pas encore clôturé.
On stocke donc **deux** créances : `pending[0..2]` de `(day, stake)`. Un check-in
encaisse toutes les créances clôturées, puis ajoute la sienne ; au plus deux
peuvent être ouvertes simultanément (J-1 non clôturé + J), car
`pool_close_delay_seconds < 86400` (validé par `ConfigParams::validate`).

Champs finaux : `pending_days: [i64; 2]`, `pending_stakes: [u64; 2]`
(`day = -1` pour un emplacement libre).

### `Config`

- `decay_bps` : 1000 par défaut.
- Ajout : `pool_close_delay_seconds: i64` (0 ≤ x < 86400, défaut 21600).
- Suppression : `reward_rate_bps`, `reward_cap`, `pool_balance`.
- `seed_pool(amount)` verse désormais dans `DayPool[today]`.

Pas de migration : le programme n'est déployé que sur devnet, on réinitialise.

## Instructions

- **`check_in(day)`** : encaisser les créances clôturées → régler les
  pénalités en retard (routées selon les règles) → exiger `staked ≥ min_stake`
  → `DayPool[today].total_stake += staked`, `winners_count += 1` → ajouter la
  créance `(today, staked)`. Plus de récompense immédiate.
- **`reap(owner)`** : régler les pénalités en retard, encaisser les créances
  clôturées. Échoue avec `NothingToReap` seulement si ni l'un ni l'autre.
  Comptes : `DayPool` du jour pénalisé et `DayPool[today]` (pour le routage),
  plus ceux des créances.
- **`finalize_exit`** : exige qu'aucune créance ne soit ouverte (pool non
  clôturé) ; encaisse les créances clôturées avant de transférer.
- Pénalités multi-jours : un `reap` après N jours d'absence règle N jours. Pour
  borner les comptes passés, seul le jour le plus récent non clôturé reçoit sa
  pénalité propre ; les jours plus anciens (déjà clôturés) vont au pool
  courant. Au plus 2 `DayPool` de destination par règlement.

## Fonctions pures (`economy.rs`)

- `share(penalties, stake, total_stake) -> u64` (u128, arrondi bas, 0 si total 0).
- `pool_closes_at(day, delay) -> i64`.
- `route_penalty(penalty_day, today, now, delay, total_stake_of_day) -> Dest`
  (`Dest::Day(d)` ou `Dest::Today`).
- `split_decay(staked, missed, bps, max) -> (lost_older, lost_last_day)` :
  sépare la perte du dernier jour manqué de celle des jours précédents, pour
  le routage. `lost_older + lost_last_day == decay(...).lost`.

## Backend (keyserver)

Job planifié :
1. **00:05 UTC** — lister les profils (`getProgramAccounts`) dont
   `settled_day < today - 1` et `active`, envoyer `reap` par lots.
2. **06:05 UTC** (après clôture) — `reap` des publieurs de la veille pour
   encaisser leurs gains. Idempotent, relance sûre.

L'API feed/profil expose `pending_gain` calculé hors chaîne (même formule) pour
l'affichage « +X SKR en attente ».

## App Android

- Profil : afficher « +X SKR en attente » et l'heure de clôture.
- Supprimer toute mention de la récompense immédiate au check-in.

## Tests

- Unitaires `economy.rs` : prorata, arrondis, `total_stake = 0`, routage avant/
  après clôture, fenêtre invalide rejetée.
- Programme (tests existants dans `programs/clockin/tests`) :
  - 3 profils, 1 absent → après clôture, gains 5/5, somme ≤ pénalité.
  - Pénalité réglée après clôture → pool du jour courant.
  - Publication à 03:00 avec J-1 non clôturé → deux créances, encaissées plus tard.
  - Réclamation avant clôture refusée ; double encaissement impossible.
  - `finalize_exit` avec créance ouverte refusé.
- Keyserver : test du job de crank contre un validateur local.
