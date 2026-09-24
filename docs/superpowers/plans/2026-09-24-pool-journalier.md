# Pool journalier — plan d'implémentation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remplacer la récompense immédiate au check-in par un pool journalier : les pénalités des absents de D sont partagées entre les publieurs de D au prorata de leur mise, réclamables après la clôture de D (D+1 06:00 UTC).

**Architecture:** Un PDA `DayPool` par jour accumule mises et pénalités. Le `Profile` garde deux créances `(day, stake)`. Une routine unique `settle()` (encaisser les créances clôturées, puis régler les jours manqués et router leurs pénalités) sert à `check_in`, `reap`, `stake`, `request_exit` et `finalize_exit`. Le pool du jour courant est un compte nommé (`init_if_needed`, graine = argument `day` vérifié). Les pools passés (créances, dernier jour manqué) passent en `remaining_accounts`, identifiés par leur adresse. Le keyserver lit la nouvelle disposition des comptes, accepte ces comptes dans le `check_in` qu'il co-signe et fait tourner un crank `reap` à 00:05 et à clôture + 5 min. L'app calcule les gains en attente à partir des `DayPool` lus sur la chaîne.

**Tech Stack:** Anchor 1.2 (Rust, litesvm pour les tests), keyserver Rust (axum, reqwest, ed25519-dalek), app Android Kotlin (web3-solana, Compose), script TS de bootstrap.

**Spec:** `docs/superpowers/specs/2026-09-24-pool-journalier-design.md` (remplace §6.1 de `docs/superpowers/specs/2026-09-14-clock-in-design.md`).

## Global Constraints

- Pénalité : `decay_bps = 1000` (10 %) par défaut, composée, bornée par `max_decay_days`.
- Gain : `penalties[D] × mise_au_check_in / total_stake[D]`, arrondi à l'inférieur, en u128 ; la poussière reste dans le vault.
- Clôture de D : `(D+1) × 86400 + pool_close_delay_seconds`, `0 ≤ pool_close_delay_seconds < 86400`, défaut `21600`.
- Avant clôture : aucune réclamation. Après clôture : aucune pénalité n'entre.
- Pénalité réglée après la clôture de son jour, ou d'un jour sans publieur → pool du jour courant.
- `settle_bound = today - 1` (inchangé) ; `reap` permissionless, sans bonus pour l'appelant.
- Graines : `DayPool` = `["day_pool", day.to_le_bytes()]`.
- `Profile` : `pending_days: [i64; 2]`, `pending_stakes: [u64; 2]`, `-1` = emplacement libre.
- `Config` : suppression de `reward_rate_bps`, `reward_cap`, `pool_balance` ; ajout de `pool_close_delay_seconds: i64`.
- Pas de migration : devnet est réinitialisé.
- Commentaires en français dans le programme et l'app, en anglais dans le keyserver (suivre chaque fichier).

## Écarts à la spec, décidés ici

1. **Ordre de règlement unique : encaisser puis pénaliser**, y compris dans `reap`, où la spec écrit l'inverse. Le gain de D arrive le matin de D+1, il subit donc la pénalité d'une absence en D+1. Deux ordres différents donneraient deux montants différents selon qui règle.
2. **`stake`, `request_exit` et `finalize_exit` règlent aussi et routent leurs pénalités.** La spec n'en parle pas, mais ils appelaient `settle_through`, qui alimentait `pool_balance`, supprimé.
3. **Argument `day: i64` ajouté** à `reap`, `stake`, `request_exit`, `finalize_exit` et `seed_pool`. Il sert à dériver le PDA du pool du jour (même règle D6 que `check_in` : il est vérifié contre l'horloge).
4. **`pending_gain` est calculé dans l'app**, pas exposé par l'API. Le keyserver n'a pas d'endpoint profil, et l'app lit déjà `Config` et `Profile` sur la chaîne.
5. **Le crank est testé par tests unitaires** (sélection des profils, octets de la transaction) plutôt que contre un validateur local. Un validateur local ne permet pas d'avancer l'horloge d'un jour. La vérification de bout en bout se fait sur devnet (tâche 8).
6. **Limite connue :** un pool du jour courant qui reçoit des pénalités routées (ou l'amorçage) sans qu'aucun publieur n'y publie reste bloqué dans le vault. La spec n'a pas prévu de report. Ce comportement est figé par un test (tâche 2) pour qu'il reste visible.

## Review Focus

1. **Client qui omet un pool attendu en `remaining_accounts`** : la transaction doit échouer (`MissingDayPool`), jamais sauter une créance ni dérouter une pénalité en silence. Test : tâche 2, `omitting_a_claimable_pool_is_refused_rather_than_skipped`.
2. **Pool de pénalité passé en lecture seule** : la transaction doit échouer, pas perdre la pénalité. Test : tâche 2, `a_read_only_penalty_pool_is_refused`.
3. **Jour sans aucun publieur** (amorçage ou pénalités routées vers aujourd'hui) : personne n'est payé à tort et le vault reste solvable. Test : tâche 2, `a_day_without_publishers_pays_nobody`.
4. **Utilisateur juste sous `min_stake` mais au-dessus grâce à un gain clôturé** : le keyserver doit l'autoriser comme le fera le programme. Test : tâche 3, `closed_gains_count_towards_eligibility`.
5. **Transaction `check_in` avec un compte supplémentaire arbitraire** (pas un pool autorisé) : le keyserver refuse de co-signer. Test : tâche 4, `refuses_extra_accounts_that_are_not_allowed_pools`.

---

## Carte des fichiers

| Fichier | Responsabilité |
|---|---|
| `program/programs/clockin/src/economy.rs` | Fonctions pures : `share`, `pool_closes_at`, `route_penalty`, `split_decay`, `valid_close_delay` |
| `program/programs/clockin/src/settlement.rs` (nouveau) | `PoolAccounts` (lecture et écriture des `DayPool` passés en comptes restants) et `settle()` |
| `program/programs/clockin/src/state.rs` | `Config`, `Profile` (créances), `DayPool` |
| `program/programs/clockin/src/instructions/*.rs` | Comptes et handlers adaptés |
| `program/programs/clockin/tests/test_pool.rs` (nouveau) | Scénarios de la spec |
| `program/programs/clockin/tests/test_layout.rs` (nouveau) | Octets de référence partagés avec le keyserver et l'app |
| `keyserver/tests/fixtures/account-layouts-v2.hex` (nouveau, généré) | Disposition des comptes, partagée entre les trois langages |
| `keyserver/src/chain.rs` | Décodeurs purs, `DayPool`, `eligible` avec gains, validateur `check_in` |
| `keyserver/src/crank.rs` (nouveau) | Sélection, transaction `reap`, planification |
| `keyserver/src/config.rs`, `main.rs` | `CRANK_KEYPAIR` optionnel et lancement du crank |
| `app/.../chain/ClockInAccounts.kt` | Nouvelles dispositions de comptes, `DayPoolAccount` |
| `app/.../chain/DailyPool.kt` (nouveau) | Miroir Kotlin : clôture, part, comptes restants |
| `app/.../chain/ClockInInstructions.kt`, `ClockInAddresses.kt` | Nouvelles listes de comptes |
| `app/.../ClockInModel.kt` | Lecture des pools, `ChainState` (gain en attente, heure de versement) |
| `app/.../ui/Profile.kt`, `i18n/Message.kt`, `ui/PublicationTime.kt` | Affichage du gain en attente, règles réécrites |
| `program/scripts/bootstrap-devnet.ts`, `docs/devnet-run.md` | Nouveaux paramètres, redéploiement |

Commandes :
- Programme : `cd program && anchor build --arch v0 && cargo test -p clockin`
- Keyserver sans base : `cd keyserver && cargo test --lib && cargo test --test android_contract`
- Keyserver API : `cd keyserver && TEST_DATABASE_URL=… cargo test --test api`
- App : `cd app && ./gradlew :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin`

**Avant la tâche 1 :** l'arbre de travail contient des modifications non commitées qui touchent les mêmes fichiers (`chain.rs`, `Profile.kt`, `ClockInModel.kt`…). Les commiter d'abord, dans un commit séparé, pour que chaque commit de ce plan ne contienne que sa tâche.

---

### Task 1 : Fonctions pures du pool (`economy.rs`)

**Files:**
- Modify: `program/programs/clockin/src/economy.rs`

**Interfaces:**
- Produces : `pub enum Dest { Day(i64), Today }`, `pub fn share(penalties: u64, stake: u64, total_stake: u64) -> u64`, `pub fn pool_closes_at(day: i64, delay: i64) -> i64`, `pub fn route_penalty(penalty_day: i64, today: i64, now: i64, delay: i64, total_stake_of_day: u64) -> Dest`, `pub fn split_decay(staked: u64, missed_days: i64, decay_bps: u16, max_decay_days: u8) -> (u64, u64)` (renvoie `(lost_older, lost_last_day)`), `pub fn valid_close_delay(delay: i64) -> bool`.
- `reward()` reste en place jusqu'à la tâche 2, qui la supprime avec son appelant.

- [ ] **Step 1 : Écrire les tests qui échouent**

Ajouter dans `mod tests` de `economy.rs` :

```rust
    #[test]
    fn share_is_pro_rata_of_the_stake() {
        assert_eq!(share(10 * SKR, 100 * SKR, 200 * SKR), 5 * SKR);
    }

    #[test]
    fn share_rounds_down_and_leaves_the_dust_in_the_vault() {
        assert_eq!(share(10, 1, 3), 3);
        let paid: u64 = (0..3).map(|_| share(10, 1, 3)).sum();
        assert!(paid <= 10);
        assert_eq!(share(1, 1, 3), 0);
    }

    #[test]
    fn share_of_a_day_without_publishers_is_zero() {
        assert_eq!(share(10 * SKR, 0, 0), 0);
        assert_eq!(share(10 * SKR, 5 * SKR, 0), 0);
    }

    #[test]
    fn share_never_exceeds_the_pool() {
        // Mise incohérente (supérieure au total) : on ne paie jamais plus que le pool.
        assert_eq!(share(10, 5, 3), 10);
        assert_eq!(share(u64::MAX, u64::MAX, u64::MAX), u64::MAX);
    }

    #[test]
    fn a_pool_closes_the_next_morning() {
        assert_eq!(pool_closes_at(100, 21_600), 101 * DAY_SECONDS + 21_600);
        assert_eq!(pool_closes_at(100, 0), 101 * DAY_SECONDS);
    }

    #[test]
    fn a_penalty_goes_to_its_own_day_while_the_pool_is_open() {
        let closes = pool_closes_at(100, 21_600);
        assert_eq!(route_penalty(100, 101, closes - 1, 21_600, 50), Dest::Day(100));
    }

    #[test]
    fn a_penalty_settled_after_closure_goes_to_today() {
        let closes = pool_closes_at(100, 21_600);
        assert_eq!(route_penalty(100, 101, closes, 21_600, 50), Dest::Today);
        assert_eq!(route_penalty(100, 102, closes + DAY_SECONDS, 21_600, 50), Dest::Today);
    }

    #[test]
    fn a_penalty_of_a_day_without_publishers_goes_to_today() {
        let closes = pool_closes_at(100, 21_600);
        assert_eq!(route_penalty(100, 101, closes - 1, 21_600, 0), Dest::Today);
    }

    #[test]
    fn today_is_never_a_penalty_destination_for_itself() {
        assert_eq!(route_penalty(101, 101, 101 * DAY_SECONDS, 21_600, 50), Dest::Today);
    }

    #[test]
    fn split_decay_isolates_the_last_missed_day() {
        assert_eq!(split_decay(100 * SKR, 0, 1000, 30), (0, 0));
        assert_eq!(split_decay(100 * SKR, 1, 1000, 30), (0, 10 * SKR));
        // 100 → 90 → 81 : 10 SKR pour les jours anciens, 9 pour le dernier.
        assert_eq!(split_decay(100 * SKR, 2, 1000, 30), (10 * SKR, 9 * SKR));
    }

    #[test]
    fn split_decay_always_sums_to_the_decay() {
        for missed in 0..40i64 {
            for staked in [0u64, 1, 7, 12_345_678, u64::MAX / 2] {
                let (older, last) = split_decay(staked, missed, 1000, 30);
                assert_eq!(older + last, decay(staked, missed, 1000, 30).lost, "missed={missed} staked={staked}");
            }
        }
    }

    #[test]
    fn the_close_delay_must_stay_within_a_day() {
        assert!(valid_close_delay(0));
        assert!(valid_close_delay(21_600));
        assert!(valid_close_delay(DAY_SECONDS - 1));
        assert!(!valid_close_delay(DAY_SECONDS));
        assert!(!valid_close_delay(-1));
    }
```

- [ ] **Step 2 : Vérifier l'échec**

Run : `cd program && cargo test -p clockin --lib economy`
Attendu : échec de compilation, `share`, `pool_closes_at`, `route_penalty`, `split_decay`, `valid_close_delay`, `Dest` introuvables.

- [ ] **Step 3 : Implémenter**

Ajouter après `settle_bound` :

```rust
/// Destination d'une pénalité réglée.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Dest {
    /// Le pool du jour pénalisé, encore ouvert et doté de publieurs.
    Day(i64),
    /// Le pool du jour courant.
    Today,
}

/// Part d'un publieur : prorata de sa mise, arrondi à l'inférieur. La poussière
/// reste dans le vault, non attribuée. Bornée par `penalties` : une mise
/// incohérente ne peut jamais faire payer plus que le pool.
pub fn share(penalties: u64, stake: u64, total_stake: u64) -> u64 {
    if total_stake == 0 {
        return 0;
    }
    let raw = penalties as u128 * stake as u128 / total_stake as u128;
    raw.min(penalties as u128) as u64
}

/// Clôture du pool de `day` : après elle, on réclame ; avant, on y verse.
pub fn pool_closes_at(day: i64, delay: i64) -> i64 {
    day.saturating_add(1)
        .saturating_mul(DAY_SECONDS)
        .saturating_add(delay)
}

/// Où verser la pénalité du jour `penalty_day`. Le total des mises d'un jour
/// passé est définitif : on ne publie pour D que pendant D.
pub fn route_penalty(
    penalty_day: i64,
    today: i64,
    now: i64,
    delay: i64,
    total_stake_of_day: u64,
) -> Dest {
    let open = penalty_day < today && now < pool_closes_at(penalty_day, delay);
    if open && total_stake_of_day > 0 {
        Dest::Day(penalty_day)
    } else {
        Dest::Today
    }
}

/// Sépare la perte du dernier jour manqué de celle des jours précédents :
/// seul le dernier peut encore avoir un pool ouvert.
/// `lost_older + lost_last_day == decay(...).lost`.
pub fn split_decay(staked: u64, missed_days: i64, decay_bps: u16, max_decay_days: u8) -> (u64, u64) {
    if missed_days <= 0 {
        return (0, 0);
    }
    let before_last = decay(staked, missed_days - 1, decay_bps, max_decay_days).remaining;
    let after = decay(staked, missed_days, decay_bps, max_decay_days).remaining;
    (staked - before_last, before_last - after)
}

/// La clôture doit tomber avant la fin du lendemain : c'est ce qui borne à deux
/// le nombre de créances ouvertes d'un profil.
pub fn valid_close_delay(delay: i64) -> bool {
    (0..DAY_SECONDS).contains(&delay)
}
```

- [ ] **Step 4 : Vérifier le succès**

Run : `cd program && cargo test -p clockin --lib economy`
Attendu : tous les tests `economy::tests::*` passent.

- [ ] **Step 5 : Commit**

```bash
git add program/programs/clockin/src/economy.rs
git commit -m "feat(program): fonctions pures du pool journalier"
```

---

### Task 2 : Programme — `DayPool`, créances et règlement routé

**Files:**
- Modify: `program/programs/clockin/Cargo.toml`, `src/constants.rs`, `src/error.rs`, `src/state.rs`, `src/lib.rs`, `src/economy.rs` (suppression de `reward`), `src/instructions/{check_in,reap,stake,exit,initialize_config,profile}.rs`
- Create: `program/programs/clockin/src/settlement.rs`
- Test: `program/programs/clockin/tests/common/mod.rs`, `tests/test_{check_in,reap,conservation,decay,exit,stake,config,profile}.rs`
- Create: `tests/test_pool.rs`, `tests/test_layout.rs`, `keyserver/tests/fixtures/account-layouts-v2.hex` (généré)

**Interfaces:**
- Consumes : Task 1 (`share`, `pool_closes_at`, `route_penalty`, `split_decay`, `Dest`, `valid_close_delay`).
- Produces (disposition Borsh, dans cet ordre, discriminant Anchor en tête) :
  - `Config` (174 octets) : `admin, publication_authority, skr_mint, vault: Pubkey`, `min_stake, faucet_amount: u64`, `withdrawal_delay_seconds, pool_close_delay_seconds: i64`, `decay_bps: u16`, `max_decay_days: u8`, `faucet_enabled: bool`, `bump, vault_bump: u8`.
  - `Profile` (127 octets) : les champs actuels inchangés, puis `pending_days: [i64; 2]`, `pending_stakes: [u64; 2]`.
  - `DayPool` (37 octets) : `day: i64, penalties: u64, total_stake: u64, winners_count: u32, bump: u8`.
  - `ConfigParams` : `min_stake: u64, faucet_amount: u64, withdrawal_delay_seconds: i64, pool_close_delay_seconds: i64, decay_bps: u16, max_decay_days: u8, faucet_enabled: bool`.
  - Instructions (arguments, comptes nommés dans l'ordre, puis pools en `remaining_accounts`) :
    - `check_in(day, commitment, blob_ref)` : `owner(s,w)`, `publication_authority(s)`, `config`, `profile(w)`, `day_pool(w)`, `check_in(w)`, `system_program`.
    - `reap(day)` : `caller(s,w)`, `owner`, `config`, `profile(w)`, `day_pool(w)`, `system_program`.
    - `stake(day, amount)` : `owner(s,w)`, `config`, `profile(w)`, `day_pool(w)`, `skr_mint`, `owner_token_account(w)`, `vault(w)`, `token_program`, `system_program`.
    - `request_exit(day)` : `owner(s,w)`, `config`, `profile(w)`, `day_pool(w)`, `system_program`.
    - `cancel_exit()` : `owner(s)`, `config`, `profile(w)`.
    - `finalize_exit(day)` : `caller(s,w)`, `owner`, `config`, `profile(w)`, `day_pool(w)`, `skr_mint`, `owner_token_account(w)`, `vault(w)`, `token_program`, `system_program`.
    - `seed_pool(day, amount)` : `admin(s,w)`, `config`, `day_pool(w)`, `skr_mint`, `admin_token_account(w)`, `vault(w)`, `token_program`, `system_program`.
  - Règle des `remaining_accounts`, que tout client applique : pour chaque créance `pending_days[i] ≥ 0` avec `now ≥ pool_closes_at(day, delay)`, le `DayPool` de ce jour en lecture. Si le profil est actif, que `bound > settled_day` et que `now < pool_closes_at(bound, delay)`, le `DayPool` de `bound` en écriture (même s'il n'existe pas encore). `bound` vaut `settle_bound(exit_unlock_at, today)`, ou `day_of(exit_unlock_at) - 1` pour `finalize_exit`.
  - Erreurs ajoutées en fin d'enum : `MissingDayPool`, `TooManyPendingClaims`, `ClaimStillOpen`.

- [ ] **Step 1 : Adapter le harness de test**

Dans `tests/common/mod.rs` :

1. Imports : ajouter `anchor_lang::solana_program::instruction::AccountMeta`, `clockin::state::DayPool`, `clockin::economy::{pool_closes_at, settle_bound}`.
2. Constante : `pub const CLOSE_DELAY: i64 = 21_600;`
3. `Ctx` gagne `pub origin_day: i64`, fixé dans `empty()` à `1_789_041_600 / DAY`.
4. Remplacer les paramètres de `initialize_config_with` par :

```rust
        let mut params = clockin::instructions::ConfigParams {
            min_stake: MIN_STAKE,
            faucet_amount: FAUCET_AMOUNT,
            withdrawal_delay_seconds: 172_800,
            pool_close_delay_seconds: CLOSE_DELAY,
            decay_bps: 2500,
            max_decay_days: 30,
            faucet_enabled: true,
        };
```

   et, dans `update_config_as`, reconstruire les paramètres courants avec les mêmes sept champs lus dans `current`.
5. Ajouter les helpers :

```rust
    pub fn day_pool_address(&self, day: i64) -> Pubkey {
        Pubkey::find_program_address(
            &[clockin::constants::DAY_POOL_SEED, &day.to_le_bytes()],
            &clockin::id(),
        )
        .0
    }

    pub fn day_pool_state(&self, day: i64) -> Option<DayPool> {
        let account = self.svm.get_account(&self.day_pool_address(day))?;
        if account.data.is_empty() {
            return None;
        }
        Some(DayPool::try_deserialize(&mut account.data.as_slice()).unwrap())
    }

    /// Pénalités et amorçages entrés dans les pools depuis le début du test.
    pub fn pooled(&self) -> u64 {
        (self.origin_day - 1..=self.today())
            .filter_map(|day| self.day_pool_state(day))
            .map(|pool| pool.penalties)
            .sum()
    }

    /// Avance jusqu'à la prochaine occurrence de cette seconde de la journée UTC.
    pub fn warp_to_next(&mut self, second_of_day: i64) {
        let now = self.now();
        let mut target = now.div_euclid(DAY) * DAY + second_of_day;
        if target <= now {
            target += DAY;
        }
        self.warp_seconds(target - now);
    }

    /// Comptes restants qu'un client honnête joint pour régler jusqu'à `bound`.
    pub fn pool_metas(&self, owner: &Pubkey, bound: i64) -> Vec<AccountMeta> {
        let Some(account) = self.svm.get_account(&self.profile_address(owner)) else {
            return vec![];
        };
        if account.data.is_empty() {
            return vec![];
        }
        let profile = Profile::try_deserialize(&mut account.data.as_slice()).unwrap();
        let now = self.now();
        let delay = self.config_state().pool_close_delay_seconds;
        let mut metas: Vec<AccountMeta> = profile
            .pending_days
            .iter()
            .filter(|day| **day >= 0 && now >= pool_closes_at(**day, delay))
            .map(|day| AccountMeta::new_readonly(self.day_pool_address(*day), false))
            .collect();
        if profile.active && bound > profile.settled_day && now < pool_closes_at(bound, delay) {
            metas.push(AccountMeta::new(self.day_pool_address(bound), false));
        }
        metas
    }

    /// Pools d'un règlement ordinaire (hors finalisation de sortie).
    pub fn honest_pools(&self, owner: &Pubkey) -> Vec<AccountMeta> {
        let unlock = self
            .svm
            .get_account(&self.profile_address(owner))
            .filter(|account| !account.data.is_empty())
            .map(|account| Profile::try_deserialize(&mut account.data.as_slice()).unwrap().exit_unlock_at)
            .unwrap_or(0);
        self.pool_metas(owner, settle_bound(unlock, self.today()))
    }
```

6. Réécrire les constructeurs d'instructions (même style qu'aujourd'hui, les pools ajoutés avec `accounts.extend(...)`) :
   - `seed_pool(amount)` : `day = self.today()`, comptes `SeedPool { admin, config, day_pool: self.day_pool_address(day), skr_mint, admin_token_account, vault, token_program, system_program }`, données `SeedPool { day, amount }`.
   - `stake(user, amount)` : `day = self.today()`, comptes `Stake { …, day_pool, …, system_program }` plus `self.honest_pools(&user.pubkey())`, données `Stake { day, amount }`.
   - `check_in_full(...)` : comptes `CheckInAccounts { owner, publication_authority, config, profile, day_pool: self.day_pool_address(day), check_in, system_program }` plus `self.honest_pools(&user.pubkey())`.
   - `reap(owner)` devient `self.reap_with(owner, self.today(), self.honest_pools(owner))`, et l'on ajoute :

```rust
    pub fn reap_with(&mut self, owner: &Pubkey, day: i64, pools: Vec<AccountMeta>) -> TransactionResult {
        let mut accounts = clockin::accounts::Reap {
            caller: self.admin.pubkey(),
            owner: *owner,
            config: self.config,
            profile: self.profile_address(owner),
            day_pool: self.day_pool_address(day),
            system_program: system_program::ID,
        }
        .to_account_metas(None);
        accounts.extend(pools);
        let instruction = Instruction {
            program_id: clockin::id(),
            accounts,
            data: clockin::instruction::Reap { day }.data(),
        };
        let admin = self.admin.insecure_clone();
        self.send(&[instruction], &[&admin])
    }
```

   - `request_exit(user)` : comptes `RequestExit { owner, config, profile, day_pool, system_program }` plus `honest_pools`, données `RequestExit { day }`. `cancel_exit(user)` : comptes `CancelExit { owner, config, profile }`, données `CancelExit {}`. Supprimer `exit_request_instruction`.
   - `finalize_exit_as(caller, owner, token)` : `day = self.today()`, `bound = self.profile_state(&self.profile_address(owner)).exit_unlock_at.div_euclid(DAY) - 1`, comptes `FinalizeExit { caller, owner, config, profile, day_pool, skr_mint, owner_token_account, vault, token_program, system_program }` plus `self.pool_metas(owner, bound)`, données `FinalizeExit { day }`.

- [ ] **Step 2 : Écrire les tests de la spec qui échouent**

Créer `tests/test_pool.rs` :

```rust
mod common;

use anchor_lang::solana_program::instruction::AccountMeta;
use clockin::economy::share;
use common::{Ctx, User, FAUCET_AMOUNT, SKR};

const HOUR: i64 = 3_600;

/// Paramètres de la spec : 10 % par jour manqué.
fn ctx() -> Ctx {
    let mut ctx = Ctx::empty();
    ctx.initialize_config_with(|params| params.decay_bps = 1000).unwrap();
    ctx
}

/// A, B et C publient le jour D0 ; seuls A et B publient D1. Renvoie D1.
fn three_members_one_absent(ctx: &mut Ctx) -> (User, User, User, i64) {
    let (a, b, c) = (ctx.new_user(), ctx.new_user(), ctx.new_user());
    for user in [&a, &b, &c] {
        ctx.stake(user, 100 * SKR).unwrap();
        ctx.check_in(user).unwrap();
    }
    ctx.warp_days(1);
    let d1 = ctx.today();
    ctx.check_in(&a).unwrap();
    ctx.check_in(&b).unwrap();
    (a, b, c, d1)
}

#[test]
fn an_absent_member_pays_the_publishers_of_the_day_pro_rata() {
    let mut ctx = ctx();
    let (a, b, c, d1) = three_members_one_absent(&mut ctx);

    ctx.warp_to_next(5 * 60); // D2 00:05 : le crank règle les absents
    ctx.reap(&c.pubkey()).unwrap();
    assert_eq!(ctx.profile_state(&c.profile).staked, 90 * SKR);
    let pool = ctx.day_pool_state(d1).unwrap();
    assert_eq!(pool.penalties, 10 * SKR);
    assert_eq!(pool.total_stake, 200 * SKR);
    assert_eq!(pool.winners_count, 2);

    assert!(ctx.reap(&a.pubkey()).is_err(), "le pool de la veille n'est pas clôturé");

    ctx.warp_to_next(6 * HOUR + 5 * 60); // D2 06:05 : après la clôture
    ctx.reap(&a.pubkey()).unwrap();
    ctx.reap(&b.pubkey()).unwrap();
    assert_eq!(ctx.profile_state(&a.profile).staked, 105 * SKR);
    assert_eq!(ctx.profile_state(&b.profile).staked, 105 * SKR);
    assert!(ctx.reap(&a.pubkey()).is_err(), "une créance ne s'encaisse qu'une fois");
    assert_eq!(ctx.profile_state(&a.profile).staked, 105 * SKR);
    assert_eq!(ctx.vault_balance(), 300 * SKR, "aucun token créé ni perdu");
}

#[test]
fn a_penalty_settled_after_the_closure_feeds_the_current_pool() {
    let mut ctx = ctx();
    let (_a, _b, c, d1) = three_members_one_absent(&mut ctx);

    ctx.warp_to_next(7 * HOUR); // D2 07:00 : le pool de D1 est clôturé
    ctx.reap(&c.pubkey()).unwrap();

    assert_eq!(ctx.day_pool_state(d1).unwrap().penalties, 0);
    assert_eq!(ctx.day_pool_state(ctx.today()).unwrap().penalties, 10 * SKR);
}

#[test]
fn publishing_before_the_previous_pool_closes_keeps_two_claims() {
    let mut ctx = ctx();
    let (a, c) = (ctx.new_user(), ctx.new_user());
    ctx.stake(&a, 100 * SKR).unwrap();
    ctx.stake(&c, 100 * SKR).unwrap();
    let d0 = ctx.today();
    ctx.check_in(&a).unwrap(); // C manque D0

    ctx.warp_to_next(3 * HOUR); // D1 03:00
    ctx.reap(&c.pubkey()).unwrap();
    assert_eq!(ctx.day_pool_state(d0).unwrap().penalties, 10 * SKR);
    ctx.check_in(&a).unwrap();
    let profile = ctx.profile_state(&a.profile);
    assert_eq!(profile.pending_days, [d0, d0 + 1]);
    assert_eq!(profile.pending_stakes, [100 * SKR, 100 * SKR]);
    assert_eq!(profile.staked, 100 * SKR, "rien d'encaissé avant la clôture");

    ctx.warp_to_next(12 * HOUR); // D1 12:00 : D0 clôturé, D1 encore ouvert
    ctx.reap(&a.pubkey()).unwrap();
    let profile = ctx.profile_state(&a.profile);
    assert_eq!(profile.staked, 110 * SKR);
    assert_eq!(profile.pending_days, [-1, d0 + 1]);
}

#[test]
fn finalizing_while_a_claim_is_still_open_is_refused() {
    let mut ctx = ctx();
    ctx.update_config(|params| params.withdrawal_delay_seconds = 13 * HOUR).unwrap();
    let a = ctx.new_user();
    ctx.stake(&a, 100 * SKR).unwrap();
    ctx.check_in(&a).unwrap();
    ctx.request_exit(&a).unwrap(); // déblocage à D1 01:00

    ctx.warp_to_next(2 * HOUR);
    assert!(
        ctx.finalize_exit(&a.pubkey(), &a.token_account).is_err(),
        "la part de D0 n'est pas encore réclamable"
    );

    ctx.warp_to_next(6 * HOUR); // D1 06:00 : clôture de D0
    ctx.finalize_exit(&a.pubkey(), &a.token_account).unwrap();
    assert_eq!(ctx.token_balance(&a.token_account), FAUCET_AMOUNT);
    assert!(!ctx.profile_state(&a.profile).active);
}

#[test]
fn the_seed_is_shared_by_the_publishers_of_the_day() {
    let mut ctx = ctx();
    let a = ctx.new_user();
    ctx.stake(&a, 100 * SKR).unwrap();
    ctx.seed_pool(20 * SKR).unwrap();
    ctx.check_in(&a).unwrap();

    ctx.warp_to_next(6 * HOUR + 60);
    ctx.reap(&a.pubkey()).unwrap();
    assert_eq!(ctx.profile_state(&a.profile).staked, 120 * SKR);
}

#[test]
fn omitting_a_claimable_pool_is_refused_rather_than_skipped() {
    let mut ctx = ctx();
    let (a, _b, c, _d1) = three_members_one_absent(&mut ctx);
    ctx.warp_to_next(5 * 60);
    ctx.reap(&c.pubkey()).unwrap();
    ctx.warp_to_next(6 * HOUR + 5 * 60);

    let today = ctx.today();
    assert!(ctx.reap_with(&a.pubkey(), today, vec![]).is_err());
    assert_eq!(ctx.profile_state(&a.profile).staked, 100 * SKR);
    assert_ne!(ctx.profile_state(&a.profile).pending_days, [-1, -1]);
}

#[test]
fn a_read_only_penalty_pool_is_refused() {
    let mut ctx = ctx();
    let (_a, _b, c, d1) = three_members_one_absent(&mut ctx);
    ctx.warp_to_next(5 * 60);

    let penalty_pool = ctx.day_pool_address(d1);
    let pools: Vec<AccountMeta> = ctx
        .honest_pools(&c.pubkey())
        .into_iter()
        .map(|meta| if meta.pubkey == penalty_pool { AccountMeta::new_readonly(meta.pubkey, false) } else { meta })
        .collect();
    let today = ctx.today();
    assert!(ctx.reap_with(&c.pubkey(), today, pools).is_err());
    assert_eq!(ctx.day_pool_state(d1).unwrap().penalties, 0);
}

#[test]
fn a_stale_day_argument_is_refused() {
    let mut ctx = ctx();
    let (_a, _b, c, _d1) = three_members_one_absent(&mut ctx);
    ctx.warp_to_next(5 * 60);
    let pools = ctx.honest_pools(&c.pubkey());
    let yesterday = ctx.today() - 1;
    assert!(ctx.reap_with(&c.pubkey(), yesterday, pools).is_err());
}

#[test]
fn a_day_without_publishers_pays_nobody() {
    // Limite connue : l'amorçage d'un jour sans publieur reste dans le vault.
    let mut ctx = ctx();
    let a = ctx.new_user();
    ctx.stake(&a, 100 * SKR).unwrap();
    ctx.seed_pool(20 * SKR).unwrap(); // personne ne publie D0

    ctx.warp_days(1);
    ctx.check_in(&a).unwrap(); // A paie D0 : 10 SKR vers le pool de D1
    ctx.warp_days(1);
    ctx.check_in(&a).unwrap(); // encaisse sa part de D1

    let profile = ctx.profile_state(&a.profile);
    assert_eq!(profile.staked, 100 * SKR, "90 SKR + sa propre pénalité, rien de l'amorçage");
    assert_eq!(ctx.vault_balance(), 120 * SKR);
    let owed = profile.staked
        + (0..2)
            .filter(|i| profile.pending_days[*i] >= 0)
            .map(|i| {
                let pool = ctx.day_pool_state(profile.pending_days[i]).unwrap();
                share(pool.penalties, profile.pending_stakes[i], pool.total_stake)
            })
            .sum::<u64>();
    assert!(ctx.vault_balance() >= owed);
}
```

> Le dernier test vérifie aussi une conséquence du routage : un membre seul qui manque D0 puis publie D1 voit sa pénalité de D0 versée au pool de D1, et la récupère. D0 n'a pas de publieur, donc elle va au pool courant. Si l'exécutant estime que ce comportement contredit l'intention de la spec, il s'arrête et le signale : le code, lui, suit la spec à la lettre.

Créer `tests/test_layout.rs` (hôte seul, sans `.so`) :

```rust
//! Octets de référence des comptes, relus par le keyserver et l'app : si l'un des
//! trois décodeurs diverge de la disposition Borsh, son test tombe.
use anchor_lang::{prelude::Pubkey, AccountSerialize};
use clockin::state::{Config, DayPool, Profile};

const FIXTURE: &str = concat!(
    env!("CARGO_MANIFEST_DIR"),
    "/../../../keyserver/tests/fixtures/account-layouts-v2.hex"
);
const SKR: u64 = 1_000_000_000;

fn hex(account: &impl AccountSerialize) -> String {
    let mut bytes = Vec::new();
    account.try_serialize(&mut bytes).unwrap();
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

#[test]
fn account_layouts_match_the_shared_fixture() {
    let config = Config {
        admin: Pubkey::new_from_array([1; 32]),
        publication_authority: Pubkey::new_from_array([2; 32]),
        skr_mint: Pubkey::new_from_array([3; 32]),
        vault: Pubkey::new_from_array([4; 32]),
        min_stake: 500 * SKR,
        faucet_amount: 1_000 * SKR,
        withdrawal_delay_seconds: 172_800,
        pool_close_delay_seconds: 21_600,
        decay_bps: 1000,
        max_decay_days: 30,
        faucet_enabled: true,
        bump: 254,
        vault_bump: 253,
    };
    let profile = Profile {
        owner: Pubkey::new_from_array([5; 32]),
        staked: 123 * SKR,
        settled_day: 20_718,
        last_checkin_day: 20_718,
        exit_requested_at: 0,
        exit_unlock_at: 0,
        total_checkins: 7,
        streak: 3,
        active: true,
        faucet_claimed: true,
        bump: 252,
        pending_days: [20_717, 20_718],
        pending_stakes: [100 * SKR, 110 * SKR],
    };
    let pool = DayPool { day: 20_718, penalties: 30 * SKR, total_stake: 600 * SKR, winners_count: 6, bump: 251 };
    let actual = format!("config={}\nprofile={}\nday_pool={}\n", hex(&config), hex(&profile), hex(&pool));
    if std::env::var("UPDATE_LAYOUTS").is_ok() {
        std::fs::write(FIXTURE, &actual).unwrap();
    }
    assert_eq!(std::fs::read_to_string(FIXTURE).unwrap(), actual);
}
```

- [ ] **Step 3 : Vérifier l'échec**

Run : `cd program && cargo test -p clockin --test test_pool --test test_layout`
Attendu : échec de compilation (`DayPool`, `pool_close_delay_seconds`, `pending_days`, `DAY_POOL_SEED`, `accounts::RequestExit`… introuvables).

- [ ] **Step 4 : État, constantes, erreurs**

`Cargo.toml` : `anchor-lang = { version = "1.2.0", features = ["init-if-needed"] }`.

`constants.rs` : ajouter

```rust
#[constant]
pub const DAY_POOL_SEED: &[u8] = b"day_pool";
```

`error.rs` : ajouter en fin d'enum (les codes existants ne bougent pas)

```rust
    #[msg("Un pool de jour attendu manque dans les comptes fournis")]
    MissingDayPool,
    #[msg("Trop de créances ouvertes sur ce profil")]
    TooManyPendingClaims,
    #[msg("Une part du pool n'est pas encore réclamable")]
    ClaimStillOpen,
```

`state.rs` : remplacer `Config` par la disposition de l'interface. Retirer `pool_balance`, `reward_cap`, `reward_rate_bps`, ajouter après `withdrawal_delay_seconds` :

```rust
    /// Délai après la fin du jour D avant la clôture de son pool (spec pool journalier).
    pub pool_close_delay_seconds: i64,
```

Ajouter à la fin de `Profile` :

```rust
    /// Créances sur les pools des jours publiés : `NO_PENDING_DAY` = emplacement
    /// libre. Deux suffisent : J-1 pas encore clôturé et J.
    pub pending_days: [i64; PENDING_SLOTS],
    pub pending_stakes: [u64; PENDING_SLOTS],
```

Ajouter :

```rust
pub const PENDING_SLOTS: usize = 2;
pub const NO_PENDING_DAY: i64 = -1;

/// PDA par jour, graines `DAY_POOL_SEED || day` : mises des publieurs du jour
/// et pénalités à leur partager.
#[account]
#[derive(InitSpace)]
pub struct DayPool {
    pub day: i64,
    pub penalties: u64,
    pub total_stake: u64,
    /// Affichage seulement.
    pub winners_count: u32,
    pub bump: u8,
}

impl DayPool {
    /// Idempotent : `init_if_needed` ne dit pas si le compte vient d'être créé.
    pub fn open(&mut self, day: i64, bump: u8) {
        self.day = day;
        self.bump = bump;
    }
}
```

Dans `impl Profile` : supprimer `settle_through` (remplacé par `settlement::settle`), garder `settle_bound`, ajouter

```rust
    pub fn has_pending(&self) -> bool {
        self.pending_days.iter().any(|day| *day != NO_PENDING_DAY)
    }

    pub fn add_claim(&mut self, day: i64, stake: u64) -> Result<()> {
        let slot = self
            .pending_days
            .iter()
            .position(|d| *d == NO_PENDING_DAY)
            .ok_or(ClockInError::TooManyPendingClaims)?;
        self.pending_days[slot] = day;
        self.pending_stakes[slot] = stake;
        Ok(())
    }
```

(imports : `use crate::{economy::settle_bound, error::ClockInError};`). Supprimer `reward()` et ses quatre tests dans `economy.rs`.

- [ ] **Step 5 : Module de règlement**

Créer `src/settlement.rs` et le déclarer dans `lib.rs` (`pub mod settlement;`) :

```rust
//! Encaissement des créances et routage des pénalités (spec pool journalier).
use anchor_lang::prelude::*;

use crate::{
    constants::DAY_POOL_SEED,
    economy::{day_of, pool_closes_at, route_penalty, share, split_decay, Dest},
    error::ClockInError,
    state::{Config, DayPool, Profile, NO_PENDING_DAY, PENDING_SLOTS},
};

/// Pools passés en `remaining_accounts` : ceux des créances clôturées (lus) et
/// celui du dernier jour manqué (écrit). L'adresse fait foi, pas l'ordre.
pub struct PoolAccounts<'a, 'info> {
    accounts: &'a [AccountInfo<'info>],
    program_id: &'a Pubkey,
}

impl<'a, 'info> PoolAccounts<'a, 'info> {
    pub fn new(accounts: &'a [AccountInfo<'info>], program_id: &'a Pubkey) -> Self {
        Self { accounts, program_id }
    }

    /// Un compte attendu et absent est une erreur : sauter une créance ou
    /// dérouter une pénalité en silence serait pire qu'un échec.
    fn find(&self, day: i64) -> Result<&'a AccountInfo<'info>> {
        let (address, _) =
            Pubkey::find_program_address(&[DAY_POOL_SEED, &day.to_le_bytes()], self.program_id);
        self.accounts
            .iter()
            .find(|info| info.key == &address)
            .ok_or_else(|| error!(ClockInError::MissingDayPool))
    }

    /// `None` : personne n'a publié ce jour-là, le pool n'a jamais été créé.
    fn read(&self, day: i64) -> Result<Option<DayPool>> {
        let info = self.find(day)?;
        if info.owner != self.program_id || info.data_is_empty() {
            return Ok(None);
        }
        let data = info.try_borrow_data()?;
        Ok(Some(DayPool::try_deserialize(&mut &data[..])?))
    }

    fn add_penalty(&self, day: i64, amount: u64) -> Result<()> {
        let info = self.find(day)?;
        require!(info.is_writable, ClockInError::MissingDayPool);
        let mut pool = self
            .read(day)?
            .ok_or_else(|| error!(ClockInError::MissingDayPool))?;
        pool.penalties = pool
            .penalties
            .checked_add(amount)
            .ok_or(ClockInError::InvalidAmount)?;
        let mut data = info.try_borrow_mut_data()?;
        pool.try_serialize(&mut &mut data[..])?;
        Ok(())
    }
}

/// Encaisse les créances clôturées, puis règle les jours manqués jusqu'à
/// `through_day`. Encaisser d'abord : le gain de D arrive le matin de D+1 et
/// subit donc, comme le reste du solde, la pénalité d'une absence en D+1.
/// Renvoie le nombre d'opérations faites ; `reap` refuse zéro.
pub fn settle(
    profile: &mut Profile,
    config: &Config,
    today_pool: &mut DayPool,
    pools: &PoolAccounts,
    now: i64,
    through_day: i64,
) -> Result<u32> {
    let delay = config.pool_close_delay_seconds;
    let mut work = 0;
    for slot in 0..PENDING_SLOTS {
        let day = profile.pending_days[slot];
        if day == NO_PENDING_DAY || now < pool_closes_at(day, delay) {
            continue;
        }
        let pool = pools
            .read(day)?
            .ok_or_else(|| error!(ClockInError::MissingDayPool))?;
        let gain = share(pool.penalties, profile.pending_stakes[slot], pool.total_stake);
        profile.staked = profile
            .staked
            .checked_add(gain)
            .ok_or(ClockInError::InvalidAmount)?;
        profile.pending_days[slot] = NO_PENDING_DAY;
        profile.pending_stakes[slot] = 0;
        work += 1;
    }

    let missed = through_day - profile.settled_day;
    if !profile.active || missed <= 0 {
        return Ok(work);
    }
    let (older, last) = split_decay(
        profile.staked,
        missed,
        config.decay_bps,
        config.max_decay_days,
    );
    profile.staked -= older + last;
    profile.settled_day = through_day;
    profile.streak = 0;

    // Un pool clôturé ne reçoit plus rien : inutile d'exiger son compte.
    let total_stake = if now < pool_closes_at(through_day, delay) {
        pools.read(through_day)?.map_or(0, |pool| pool.total_stake)
    } else {
        0
    };
    let mut to_today = older;
    match route_penalty(through_day, day_of(now), now, delay, total_stake) {
        Dest::Day(day) => pools.add_penalty(day, last)?,
        Dest::Today => {
            to_today = to_today
                .checked_add(last)
                .ok_or(ClockInError::InvalidAmount)?
        }
    }
    today_pool.penalties = today_pool
        .penalties
        .checked_add(to_today)
        .ok_or(ClockInError::InvalidAmount)?;
    Ok(work + 1)
}
```

- [ ] **Step 6 : Instructions**

Règle commune à chaque handler qui règle : lire l'horloge, `require!(day == today, ClockInError::DayMismatch)`, construire `let pools = PoolAccounts::new(ctx.remaining_accounts, ctx.program_id);` *avant* d'emprunter `ctx.accounts`, puis `accounts.day_pool.open(today, ctx.bumps.day_pool)`. Le compte `config` n'est plus `mut` nulle part sauf dans `UpdateConfig` (moins de verrous d'écriture concurrents). Chaque `day_pool` nommé se déclare ainsi (payeur : le signataire mutable de l'instruction) :

```rust
    /// Pool du jour : reçoit les pénalités routées vers aujourd'hui.
    #[account(
        init_if_needed,
        payer = owner,
        space = 8 + DayPool::INIT_SPACE,
        seeds = [DAY_POOL_SEED, &day.to_le_bytes()],
        bump
    )]
    pub day_pool: Account<'info, DayPool>,
```

avec `#[instruction(day: i64)]` sur la structure et `system_program: Program<'info, System>` en dernier compte nommé.

`check_in.rs` : ajouter `day_pool` (payer `owner`) entre `profile` et `check_in`, et remplacer le corps du handler, après la vérification du jour, par :

```rust
    let pools = PoolAccounts::new(ctx.remaining_accounts, ctx.program_id);
    let accounts = &mut ctx.accounts;
    accounts.day_pool.open(today, ctx.bumps.day_pool);
    require!(accounts.profile.active, ClockInError::PositionInactive);
    if accounts.profile.exit_unlock_at > 0 {
        require!(now < accounts.profile.exit_unlock_at, ClockInError::ExitUnlocked);
    }

    let bound = accounts.profile.settle_bound(today);
    settle(&mut accounts.profile, &accounts.config, &mut accounts.day_pool, &pools, now, bound)?;
    let staked = accounts.profile.staked;
    require!(staked >= accounts.config.min_stake, ClockInError::InsufficientStake);

    // Plus de récompense immédiate : la mise entre au pool du jour, la part se
    // réclame après sa clôture.
    let pool = &mut accounts.day_pool;
    pool.total_stake = pool.total_stake.checked_add(staked).ok_or(ClockInError::InvalidAmount)?;
    pool.winners_count = pool.winners_count.saturating_add(1);

    let profile = &mut accounts.profile;
    profile.add_claim(today, staked)?;
    profile.streak += 1;
    profile.settled_day = today;
    profile.last_checkin_day = today;
    profile.total_checkins += 1;
```

suivi de l'écriture du `CheckIn` (inchangée, avec `accounts.check_in`), où `now = clock.unix_timestamp`.

`reap.rs` : `caller` devient `#[account(mut)]`, `day_pool` (payer `caller`) et `system_program` ajoutés, handler :

```rust
pub fn handle_reap(ctx: Context<Reap>, day: i64) -> Result<()> {
    let now = Clock::get()?.unix_timestamp;
    let today = day_of(now);
    require!(day == today, ClockInError::DayMismatch);
    let pools = PoolAccounts::new(ctx.remaining_accounts, ctx.program_id);
    let accounts = &mut ctx.accounts;
    accounts.day_pool.open(today, ctx.bumps.day_pool);
    require!(accounts.profile.active, ClockInError::PositionInactive);

    let bound = accounts.profile.settle_bound(today);
    let work = settle(&mut accounts.profile, &accounts.config, &mut accounts.day_pool, &pools, now, bound)?;
    require!(work > 0, ClockInError::NothingToReap);
    Ok(())
}
```

`stake.rs` : `day_pool` (payer `owner`) après `profile`, `system_program` en dernier, signature `handle_stake(ctx, day, amount)`. La branche `if profile.active` appelle `settle(...)` avec `bound = settle_bound(today)`, la branche d'ouverture est inchangée, le transfert aussi.

`exit.rs` :
- `RequestExit` (nouvelle structure) : `owner` (`mut`, payeur), `config`, `profile`, `day_pool`, `system_program`. `handle_request_exit(ctx, day)` règle avec `settle(...)` jusqu'à `settle_bound(today)` au lieu de `settle_through`, puis fige le déblocage comme aujourd'hui.
- `CancelExit` : l'ancienne `ExitRequest` renommée, `config` sans `mut`. Handler inchangé.
- `FinalizeExit` : `caller` en `mut` (payeur du `day_pool`), `day_pool` après `profile`, `system_program` en dernier. Dans `handle_finalize_exit(ctx, day)`, remplacer `settle_through` par :

```rust
        let bound = day_of(accounts.profile.exit_unlock_at) - 1;
        settle(&mut accounts.profile, &accounts.config, &mut accounts.day_pool, &pools, now, bound)?;
        // Une part encore ouverte serait perdue avec la position : on attend sa clôture.
        require!(!accounts.profile.has_pending(), ClockInError::ClaimStillOpen);
```

`initialize_config.rs` : `ConfigParams` suit l'interface, et `validate` devient

```rust
        require!(self.decay_bps <= 10_000, ClockInError::InvalidConfigParam);
        require!(self.max_decay_days >= 1, ClockInError::InvalidConfigParam);
        require!(self.withdrawal_delay_seconds >= 0, ClockInError::InvalidConfigParam);
        require!(valid_close_delay(self.pool_close_delay_seconds), ClockInError::InvalidConfigParam);
```

`handle_initialize_config` et `handle_update_config` écrivent `pool_close_delay_seconds` et plus aucun champ de récompense. `SeedPool` : `#[instruction(day: i64)]`, `config` sans `mut`, `day_pool` (payer `admin`) après `config`, `system_program` en dernier. `handle_seed_pool(ctx, day, amount)` vérifie le jour, transfère comme aujourd'hui, puis

```rust
    let pool = &mut ctx.accounts.day_pool;
    pool.open(day, ctx.bumps.day_pool);
    pool.penalties = pool.penalties.checked_add(amount).ok_or(ClockInError::InvalidAmount)?;
```

`profile.rs` : `handle_create_profile` initialise `pending_days = [NO_PENDING_DAY; PENDING_SLOTS]` et `pending_stakes = [0; PENDING_SLOTS]`.

`lib.rs` : signatures `seed_pool(ctx, day, amount)`, `stake(ctx, day, amount)`, `reap(ctx, day)`, `request_exit(ctx: Context<RequestExit>, day)`, `cancel_exit(ctx: Context<CancelExit>)`, `finalize_exit(ctx, day)`. L'ordre des arguments suit `#[instruction(day: i64)]` : `day` d'abord.

- [ ] **Step 7 : Générer la fixture de disposition**

Run : `cd program && anchor build --arch v0 && UPDATE_LAYOUTS=1 cargo test -p clockin --test test_layout`
Puis vérifier les longueurs : `awk -F= '{print $1, length($2)/2}' ../keyserver/tests/fixtures/account-layouts-v2.hex`
Attendu : `config 174`, `profile 127`, `day_pool 37`.

- [ ] **Step 8 : Adapter les tests existants**

- `test_check_in.rs` : le premier test devient `a_check_in_records_a_claim_instead_of_paying_a_reward`. Il sème le pool de 100 SKR, mise 50 et publie, puis vérifie `staked == 50 * SKR`, `pending_days[0] == today`, `pending_stakes[0] == 50 * SKR`, `day_pool_state(today).total_stake == 50 * SKR`, `winners_count == 1`, `penalties == 100 * SKR`, et garde les assertions sur le `CheckIn`. Supprimer `an_empty_pool_pays_nothing_but_the_check_in_still_succeeds` (sans objet).
- `test_reap.rs` : `config_state().pool_balance` → `pooled()` (17,5 SKR inchangés : membre seul, jours sans publieur). Dans `reap_then_check_in_the_same_day_does_not_decay_twice`, remplacer le calcul de récompense par `assert_eq!(profile.staked, after_reap)`.
- `test_decay.rs` : premier test, `staked == 30 * SKR` et `pooled() == 10 * SKR`, commentaire mis à jour (plus de récompense : la pénalité part au pool du jour courant, faute de publieur la veille). Dernier test : `pooled() == 80 * SKR`.
- `test_exit.rs` et `test_stake.rs` : `config_state().pool_balance` → `pooled()`, mêmes valeurs. Membre seul : il ne publie jamais un jour où il est pénalisé, donc aucune part ne revient.
- `test_config.rs` : supprimer les assertions `pool_balance`, `reward_rate_bps` et `reward_cap`, ajouter `assert_eq!(config.pool_close_delay_seconds, 21_600)`. `seed_pool_moves_tokens_into_the_vault_and_credits_the_pool` vérifie `day_pool_state(today).penalties == 500 * SKR`. `update_config_recalibrates_without_touching_the_pool` change `decay_bps` et `pool_close_delay_seconds = 3_600`, vérifie les deux et que `day_pool_state(today).penalties` n'a pas bougé. Ajouter `update_config_refuses_a_close_delay_of_a_full_day` (`pool_close_delay_seconds = 86_400` → `is_err()`).
- `test_profile.rs` : ajouter `assert_eq!(profile.pending_days, [-1, -1]);`.
- `test_conservation.rs` : remplacer `assert_conservation` par

```rust
/// Solvabilité : le vault couvre les mises et les parts déjà dues, et ce qui
/// reste au-delà ne vient que des pools.
fn assert_solvent(ctx: &Ctx, users: &[&User]) {
    let owed: u64 = users
        .iter()
        .map(|user| {
            let profile = ctx.profile_state(&user.profile);
            let pending: u64 = (0..2)
                .filter(|i| profile.pending_days[*i] >= 0)
                .map(|i| {
                    let pool = ctx.day_pool_state(profile.pending_days[i]).unwrap();
                    share(pool.penalties, profile.pending_stakes[i], pool.total_stake)
                })
                .sum();
            profile.staked + pending
        })
        .sum();
    let vault = ctx.vault_balance();
    assert!(vault >= owed, "le vault doit couvrir {owed}, il contient {vault}");
    assert!(vault - owed <= ctx.pooled(), "rien ne reste au vault hors des pools");
}
```

  Appeler `assert_solvent` partout où l'on appelait `assert_conservation`. Garder le scénario, et remplacer l'assertion finale par `assert!(ctx.vault_balance() <= ctx.pooled(), "il ne reste que la poussière et les pools non distribués")`.

- [ ] **Step 9 : Vérifier le succès**

Run : `cd program && anchor build --arch v0 && cargo test -p clockin`
Attendu : tout passe, y compris `test_pool` (9 tests) et `test_layout`.

- [ ] **Step 10 : Commit**

```bash
git add program/programs/clockin keyserver/tests/fixtures/account-layouts-v2.hex
git commit -m "feat(program): pool journalier réclamable après clôture"
```

---

### Task 3 : Keyserver — lecture des nouveaux comptes et éligibilité

**Files:**
- Modify: `keyserver/src/chain.rs`, `keyserver/src/api.rs`, `keyserver/tests/api.rs`

**Interfaces:**
- Consumes : `keyserver/tests/fixtures/account-layouts-v2.hex` (Task 2), la disposition des comptes (Task 2).
- Produces (dans `chain.rs`) :
  - `Config { authority, min_stake, decay_bps, max_decay_days, pool_close_delay: i64 }`
  - `Profile { owner, staked, settled_day, exit_unlock_at, active, pending_days: [i64; 2], pending_stakes: [u64; 2] }`
  - `pub struct DayPool { pub day: i64, pub penalties: u64, pub total_stake: u64 }`
  - `pub fn decode_config(bytes: &[u8]) -> Result<(Config, u8)>`, `pub fn decode_profile(bytes: &[u8]) -> Result<(Profile, u8)>`, `pub fn decode_day_pool(bytes: &[u8]) -> Result<(DayPool, u8)>` (le `u8` est le bump stocké ; aucune vérification d'adresse)
  - `pub fn closes_at(day: i64, delay: i64) -> i64`, `pub fn share(penalties: u64, stake: u64, total_stake: u64) -> u64`
  - `impl Profile { pub fn settle_bound(&self, today: i64) -> i64; pub fn closed_claims(&self, config: &Config, now: i64) -> Vec<(i64, u64)>; pub fn settlement_pools(&self, config: &Config, now: i64, bound: i64) -> Vec<(i64, bool)>; pub fn needs_reap(&self, config: &Config, now: i64) -> bool; pub fn eligible(&self, config: &Config, now: i64, gains: u64) -> bool }`
  - Trait `Chain` : ajout de `async fn day_pool(&self, day: i64) -> Result<Option<DayPool>>`.
  - `pub(crate) fn pda`, `pub(crate) fn discriminator` (déjà là, visibilité élargie pour le crank).
  - Dans `api.rs` : `async fn closed_gains(chain: &dyn Chain, profile: &Profile, config: &Config, now: i64) -> Result<u64>`.

- [ ] **Step 1 : Tests qui échouent**

Dans `mod tests` de `chain.rs`, ajouter :

```rust
    fn layout(name: &str) -> Vec<u8> {
        let text = include_str!("../tests/fixtures/account-layouts-v2.hex");
        let line = text.lines().find(|l| l.starts_with(&format!("{name}="))).unwrap();
        hex::decode(&line[name.len() + 1..]).unwrap()
    }
    #[test]
    fn decoders_match_the_program_layouts() {
        let (config, bump) = decode_config(&layout("config")).unwrap();
        assert_eq!(config.authority, [2; 32]);
        assert_eq!(config.min_stake, 500_000_000_000);
        assert_eq!(config.pool_close_delay, 21_600);
        assert_eq!((config.decay_bps, config.max_decay_days, bump), (1000, 30, 254));
        let (profile, bump) = decode_profile(&layout("profile")).unwrap();
        assert_eq!(profile.owner, [5; 32]);
        assert_eq!(profile.staked, 123_000_000_000);
        assert_eq!(profile.pending_days, [20_717, 20_718]);
        assert_eq!(profile.pending_stakes, [100_000_000_000, 110_000_000_000]);
        assert!(profile.active);
        assert_eq!(bump, 252);
        let (pool, bump) = decode_day_pool(&layout("day_pool")).unwrap();
        assert_eq!((pool.day, pool.penalties, pool.total_stake, bump), (20_718, 30_000_000_000, 600_000_000_000, 251));
    }
    fn pool_config() -> Config {
        Config { authority: [0; 32], min_stake: 100, decay_bps: 1000, max_decay_days: 30, pool_close_delay: 21_600 }
    }
    fn pool_profile() -> Profile {
        Profile {
            owner: [1; 32], staked: 1_000, settled_day: 99, exit_unlock_at: 0, active: true,
            pending_days: [98, 99], pending_stakes: [1_000, 1_000],
        }
    }
    #[test]
    fn claims_close_the_next_morning() {
        let p = pool_profile();
        let c = pool_config();
        assert_eq!(p.closed_claims(&c, closes_at(98, 21_600) - 1), vec![]);
        assert_eq!(p.closed_claims(&c, closes_at(99, 21_600) - 1), vec![(98, 1_000)]);
        assert_eq!(p.closed_claims(&c, closes_at(99, 21_600)), vec![(98, 1_000), (99, 1_000)]);
    }
    #[test]
    fn settlement_pools_follow_the_program_rule() {
        let c = pool_config();
        let mut p = pool_profile();
        // Publié le 98, absent le 99.
        p.settled_day = 98;
        p.pending_days = [98, -1];
        // Jour 100 à 03:00 : 98 clôturé (lecture), 99 manqué et encore ouvert (écriture).
        let now = 100 * 86_400 + 3 * 3_600;
        assert_eq!(p.settlement_pools(&c, now, 99), vec![(98, false), (99, true)]);
        // Après 06:00 : le pool de 99 est clôturé, on ne le passe plus.
        let now = 100 * 86_400 + 7 * 3_600;
        assert_eq!(p.settlement_pools(&c, now, 99), vec![(98, false)]);
    }
    #[test]
    fn needs_reap_when_late_or_when_a_claim_closed() {
        let c = pool_config();
        let mut p = pool_profile();
        p.pending_days = [-1, 99];
        assert!(!p.needs_reap(&c, 100 * 86_400 + 60));
        assert!(p.needs_reap(&c, 100 * 86_400 + 6 * 3_600 + 60));
        p.pending_days = [-1, -1];
        assert!(p.needs_reap(&c, 101 * 86_400 + 60), "jour 100 manqué");
        p.active = false;
        assert!(!p.needs_reap(&c, 101 * 86_400 + 60));
    }
    #[test]
    fn closed_gains_count_towards_eligibility() {
        let c = pool_config();
        let mut p = pool_profile();
        p.staked = 95;
        let now = 100 * 86_400;
        assert!(!p.eligible(&c, now, 0));
        assert!(p.eligible(&c, now, 5));
    }
```

Et `pool_profile` dans les tests existants de `eligible` : ajouter `pending_days: [-1, -1], pending_stakes: [0, 0]` et `pool_close_delay: 21_600` aux constructions de `Profile`/`Config`, et `0` comme troisième argument de chaque appel `eligible(...)`. Faire de même dans `keyserver/tests/api.rs` (constructions de `Config` et `Profile`).

- [ ] **Step 2 : Vérifier l'échec**

Run : `cd keyserver && cargo test --lib chain`
Attendu : échec de compilation (`decode_config`, `DayPool`, `closed_claims`… introuvables).

- [ ] **Step 3 : Implémenter**

Dans `chain.rs` :

```rust
pub const DAY: i64 = 86_400;
pub fn closes_at(day: i64, delay: i64) -> i64 {
    day.saturating_add(1).saturating_mul(DAY).saturating_add(delay)
}
/// Same formula as the program: pro rata, rounded down, never above the pool.
pub fn share(penalties: u64, stake: u64, total_stake: u64) -> u64 {
    if total_stake == 0 {
        return 0;
    }
    (penalties as u128 * stake as u128 / total_stake as u128).min(penalties as u128) as u64
}
#[derive(Clone, Debug)]
pub struct DayPool {
    pub day: i64,
    pub penalties: u64,
    pub total_stake: u64,
}
impl Profile {
    pub fn settle_bound(&self, today: i64) -> i64 {
        if self.exit_unlock_at > 0 {
            (today - 1).min(self.exit_unlock_at.div_euclid(DAY) - 1)
        } else {
            today - 1
        }
    }
    /// Claims whose pool is closed, as `(day, stake)`.
    pub fn closed_claims(&self, config: &Config, now: i64) -> Vec<(i64, u64)> {
        (0..2)
            .filter(|&i| self.pending_days[i] >= 0 && now >= closes_at(self.pending_days[i], config.pool_close_delay))
            .map(|i| (self.pending_days[i], self.pending_stakes[i]))
            .collect()
    }
    /// Pools a settling instruction must carry, as `(day, writable)`: closed
    /// claims (read) and the last missed day while its pool is open (written).
    pub fn settlement_pools(&self, config: &Config, now: i64, bound: i64) -> Vec<(i64, bool)> {
        let mut pools: Vec<(i64, bool)> =
            self.closed_claims(config, now).into_iter().map(|(day, _)| (day, false)).collect();
        if self.active && bound > self.settled_day && now < closes_at(bound, config.pool_close_delay) {
            pools.push((bound, true));
        }
        pools
    }
    pub fn needs_reap(&self, config: &Config, now: i64) -> bool {
        self.active
            && (self.settle_bound(now.div_euclid(DAY)) > self.settled_day
                || !self.closed_claims(config, now).is_empty())
    }
}
```

`settlement_pools` renvoie les créances dans l'ordre des emplacements, puis le jour borne. `eligible` reçoit `gains: u64` et part de `self.staked as u128 + gains as u128` (les gains sont encaissés avant le decay, comme dans le programme), et `bound` vient de `self.settle_bound(...)`.

Extraire le décodage des trois comptes en fonctions pures (mêmes vérifications qu'aujourd'hui : bornes, booléens, `r.done()`). Disposition `Config` (174 octets) : `take(40)`, `authority`, `take(64)`, `min_stake`, `take(16)`, `pool_close_delay: i64` (refus hors de `0..86_400`), `decay_bps`, `max_decay_days`, `faucet`, `bump`, `vault_bump`. `Profile` (127 octets) : les champs actuels, puis deux `i64`, puis deux `u64`. `DayPool` (37 octets) : `take(8)`, `day`, `penalties`, `total_stake`, `take(4)`, `bump`. `RpcChain::config`, `profile` et le nouveau `day_pool` lisent avec ces longueurs, décodent, puis vérifient le bump attendu (et `owner == wallet`, `stored day == day`). `day_pool` utilise `pda(&self.program, &[b"day_pool", &day.to_le_bytes()])` et le nom `"account:DayPool"`. Ajouter `day_pool` à `TestChain` dans `tests/api.rs`, lu dans un nouveau `pools: Mutex<HashMap<i64, DayPool>>`.

Dans `api.rs` :

```rust
/// Gains the program will credit before checking `min_stake`.
async fn closed_gains(chain: &dyn Chain, profile: &Profile, config: &Config, now: i64) -> Result<u64> {
    let mut total = 0u64;
    for (day, stake) in profile.closed_claims(config, now) {
        if let Some(pool) = chain.day_pool(day).await? {
            total = total.saturating_add(chain::share(pool.penalties, stake, pool.total_stake));
        }
    }
    Ok(total)
}
```

`reader()` et `submit()` calculent `gains` avec ce helper et le passent à `eligible`. (Le réordonnancement de `submit` autour du validateur arrive en Task 4.)

- [ ] **Step 4 : Vérifier le succès**

Run : `cd keyserver && cargo test --lib`, puis `TEST_DATABASE_URL=… cargo test --test api`
Attendu : PASS. (`cargo test --test android_contract` reste vert : le validateur n'a pas encore changé.)

- [ ] **Step 5 : Commit**

```bash
git add keyserver/src/chain.rs keyserver/src/api.rs keyserver/tests/api.rs
git commit -m "feat(keyserver): lecture des pools et créances, gains dans l'éligibilité"
```

---

### Task 4 : Contrat `check_in` — builder Android et validateur keyserver

**Files:**
- Modify: `app/app/src/main/java/com/clockin/hackathon/chain/ClockInAddresses.kt`, `ClockInInstructions.kt`
- Modify: `app/app/src/test/java/com/clockin/hackathon/backend/BackendTest.kt`, `app/app/src/test/java/com/clockin/hackathon/chain/InstructionsTest.kt`
- Modify: `keyserver/src/chain.rs` (`Expected`, `validate_transaction`, tests), `keyserver/src/api.rs` (`submit`), `keyserver/tests/api.rs` (`transaction()`), `keyserver/tests/android_contract.rs`, `keyserver/tests/fixtures/android-post-v1.json` (champ `transaction` régénéré)

**Interfaces:**
- Consumes : Task 3 (`Profile::settle_bound`, `pda`).
- Produces :
  - Kotlin : `ClockInAddresses.dayPool(programId: SolanaPublicKey, day: Long): SolanaPublicKey` ; `ClockInInstructions.checkIn(programId, owner, publicationAuthority, day, commitment, blobRef, pools: List<AccountMeta> = emptyList())` avec les comptes `owner(s,w)`, `authority(s)`, `config(ro)`, `profile(w)`, `dayPool(day)(w)`, `checkIn(w)`, `system(ro)`, puis `pools`.
  - Rust : `Expected` gagne `pub pools: Vec<i64>` (jours dont le `DayPool` peut figurer en compte supplémentaire). Le validateur accepte 7 comptes fixes plus jusqu'à 3 pools, chacun distinct, non signataire et dans `pools`.

- [ ] **Step 1 : Tests qui échouent**

Kotlin, `InstructionsTest.kt` : ajouter

```kotlin
    @Test fun checkInCarriesTheDayPoolAndTheSettlementPools() {
        val program = SolanaPublicKey(ByteArray(32) { 3 })
        val owner = SolanaPublicKey(ByteArray(32) { 4 })
        val authority = SolanaPublicKey(ByteArray(32) { 5 })
        val extra = AccountMeta(ClockInAddresses.dayPool(program, 99), false, false)
        val ix = ClockInInstructions.checkIn(program, owner, authority, 100, ByteArray(32), ByteArray(32), listOf(extra))
        assertEquals(8, ix.accounts.size)
        assertEquals(ClockInAddresses.config(program), ix.accounts[2].publicKey)
        assertFalse(ix.accounts[2].isWritable)
        assertEquals(ClockInAddresses.dayPool(program, 100), ix.accounts[4].publicKey)
        assertTrue(ix.accounts[4].isWritable)
        assertEquals(extra, ix.accounts[7])
    }
```

(Adapter les noms `publicKey`/`isWritable`/`accounts` à l'API web3-solana déjà utilisée dans ce fichier.)

`BackendTest.sharedRustAndroidPacket` : construire la transaction avec `pools = listOf(AccountMeta(ClockInAddresses.dayPool(SolanaPublicKey(program), post.day - 1), false, false))` et attendre l'en-tête `byteArrayOf(2, 1, 4)` (config, system, programme et le pool lu).

Rust, tests de `chain.rs` : réécrire `fixture()` avec 8 clés `[wallet, authority, profile, day_pool(day), checkin, config, system, program]`, en-tête `[2, 1, 3, 8]`, instruction `[1, 7, 7, 0, 1, 5, 2, 3, 4, 6, 80]` (index du programme 7 ; comptes wallet, authority, config=5, profile=2, day_pool=3, checkin=4, system=6), `Expected { …, pools: vec![] }`. Décaler la liste d'offsets de `refuses_noncanonical_truncated_and_modified_transactions` : `[1, 65, 129, 130, 131, 132, 133, 165, 421, 422, 423, 424, 425, 426, 427, 428, 429, 430, 431, 432, 442, 482]`. Réécrire `wallet_shaped()` avec la même logique (clés réordonnées, `day_pool` et `config` inclus, deux instructions de compute budget). Ajouter :

```rust
    #[test]
    fn accepts_allowed_pools_and_refuses_the_rest() {
        let (_, mut e, _, _) = fixture();
        e.pools = vec![e.day - 1];
        let allowed = pda(&e.program, &[b"day_pool", &(e.day - 1).to_le_bytes()]).0;
        assert!(validate_transaction(&with_extra(&e, allowed), &e).is_ok());
        assert!(validate_transaction(&with_extra(&e, [42; 32]), &e).is_err());
    }
    #[test]
    fn refuses_extra_accounts_that_are_not_allowed_pools() {
        let (_, e, _, _) = fixture(); // e.pools vide
        let pool = pda(&e.program, &[b"day_pool", &(e.day - 1).to_le_bytes()]).0;
        assert!(validate_transaction(&with_extra(&e, pool), &e).is_err());
    }
```

avec `with_extra(e, key)` qui construit la fixture en ajoutant `key` comme 9e clé en lecture seule (en-tête `[2, 1, 4, 9]`, `key` placée juste avant le programme, instruction à 8 comptes dont le dernier désigne `key`).

- [ ] **Step 2 : Vérifier l'échec**

Run : `cd keyserver && cargo test --lib chain` → échec de compilation (`Expected.pools`).
Run : `cd app && ./gradlew :app:testDebugUnitTest --tests '*InstructionsTest*'` → échec (`dayPool` introuvable).

- [ ] **Step 3 : Implémenter côté app**

`ClockInAddresses.kt` :

```kotlin
    fun dayPool(programId: SolanaPublicKey, day: Long): SolanaPublicKey =
        find(programId, listOf("day_pool".toByteArray(), BorshWriter().i64(day).build()))
```

`ClockInInstructions.checkIn` : paramètre `pools: List<AccountMeta> = emptyList()`, comptes

```kotlin
            listOf(
                AccountMeta(owner, true, true),
                AccountMeta(publicationAuthority, true, false),
                AccountMeta(ClockInAddresses.config(programId), false, false),
                AccountMeta(ClockInAddresses.profile(programId, owner), false, true),
                AccountMeta(ClockInAddresses.dayPool(programId, day), false, true),
                AccountMeta(ClockInAddresses.checkIn(programId, owner, day), false, true),
                AccountMeta(SYSTEM_PROGRAM, false, false),
            ) + pools,
```

- [ ] **Step 4 : Implémenter côté keyserver**

Dans `validate_transaction` : `count` accepté dans `8..=12`, puis pour l'instruction du programme :

```rust
            let allowed: Vec<Key> = expected
                .pools
                .iter()
                .filter(|day| **day < expected.day)
                .map(|day| pda(&expected.program, &[b"day_pool", &day.to_le_bytes()]).0)
                .collect();
            let accounts = r.short()?;
            if !(7..=10).contains(&accounts) {
                return Err(invalid());
            }
            let day_pool = pda(&expected.program, &[b"day_pool", &expected.day.to_le_bytes()]).0;
            let expected_accounts = [expected.wallet, expected.authority, config, profile, day_pool, checkin, [0; 32]];
            for (position, key) in expected_accounts.iter().enumerate() {
                let index = r.u8()? as usize;
                if keys.get(index) != Some(key)
                    || match position {
                        0 => index != 0,
                        1 => index != 1,
                        3..=5 => !writable_unsigned(index),
                        _ => !readonly_nonsigner(index), // config et system_program
                    }
                {
                    return Err(invalid());
                }
                used[index] = true;
            }
            let mut extras: Vec<Key> = Vec::new();
            for _ in 7..accounts {
                let index = r.u8()? as usize;
                let key = *keys.get(index).ok_or_else(invalid)?;
                if index < 2 || !allowed.contains(&key) || extras.contains(&key) {
                    return Err(invalid());
                }
                extras.push(key);
                used[index] = true;
            }
```

`config` est exigé en lecture seule : le programme ne l'écrit plus, et un wallet ne change pas l'écriture d'un compte en réordonnant les clés. C'est aussi ce qui rend invalide la fixture dont l'octet 131 (nombre de comptes en lecture) est altéré.

Dans `api.rs::submit`, lire la chaîne *avant* de valider pour connaître les pools autorisés :

```rust
    let (config, profile) = tokio::try_join!(app.chain.config(), app.chain.profile(wallet))?;
    let profile = profile.filter(|p| p.owner == wallet).ok_or_else(Error::forbidden)?;
    let mut pools: Vec<i64> = profile.pending_days.iter().copied().filter(|d| *d >= 0).collect();
    pools.push(profile.settle_bound(input.day));
    let expected = Expected { program: app.program, wallet, authority: app.authority.verifying_key().to_bytes(), day: input.day, commitment, blob_ref, pools };
    let blockhash = chain::validate_transaction(&transaction, &expected)?;
    let valid = app.chain.blockhash_valid(blockhash).await?;
    if config.authority != expected.authority {
        return Err(Error::unavailable());
    }
    let now = app.clock.now();
    let gains = closed_gains(app.chain.as_ref(), &profile, &config, now).await?;
    if !profile.eligible(&config, now, gains) {
        return Err(Error::forbidden());
    }
```

La suite de `submit` est inchangée. On autorise *toutes* les créances et le jour borne, pas seulement ceux que le client doit joindre : une créance qui se clôture entre la construction et la validation ne doit pas faire échouer la publication.

`tests/api.rs::transaction()` : même forme que la nouvelle `fixture()` (8 clés, `day_pool` inclus, config en lecture). `tests/android_contract.rs` : `Expected { …, pools: vec![fixture["day"].as_i64().unwrap() - 1] }`.

- [ ] **Step 5 : Régénérer la transaction Android partagée**

Run : `cd app && ./gradlew :app:testDebugUnitTest --tests '*BackendTest.sharedRustAndroidPacket*'` (échoue sur la comparaison de la transaction)
Puis copier le contenu de `app/app/build/android-transaction.txt` dans le champ `"transaction"` de `keyserver/tests/fixtures/android-post-v1.json`.
Run à nouveau : attendu PASS.

- [ ] **Step 6 : Vérifier le succès**

Run : `cd keyserver && cargo test --lib && cargo test --test android_contract && TEST_DATABASE_URL=… cargo test --test api`
Run : `cd app && ./gradlew :app:testDebugUnitTest`
Attendu : PASS partout.

- [ ] **Step 7 : Commit**

```bash
git add app/app/src/main/java/com/clockin/hackathon/chain app/app/src/test keyserver/src keyserver/tests
git commit -m "feat: check_in avec pool du jour et pools de règlement"
```

---

### Task 5 : Keyserver — crank `reap`

**Files:**
- Create: `keyserver/src/crank.rs` (déclaré dans `lib.rs`)
- Modify: `keyserver/src/chain.rs` (RPC : `profiles`, `latest_blockhash`, `send_transaction`, limite de taille paramétrable), `keyserver/src/config.rs`, `keyserver/src/main.rs`

**Interfaces:**
- Consumes : Task 3 (`Profile::needs_reap`, `settlement_pools`, `settle_bound`, `decode_profile`, `pda`, `discriminator`).
- Produces :
  - `#[async_trait] pub trait CrankChain: Send + Sync { async fn config(&self) -> Result<Config>; async fn profiles(&self) -> Result<Vec<Profile>>; async fn latest_blockhash(&self) -> Result<Key>; async fn send_transaction(&self, raw: &[u8]) -> Result<()>; }`, implémenté par `RpcChain`
  - `pub fn next_run(now: i64, close_delay: i64) -> i64`
  - `pub fn reap_transaction(program: &Key, caller: &SigningKey, owner: &Key, day: i64, pools: &[(i64, bool)], blockhash: &Key) -> Vec<u8>`
  - `pub struct CrankReport { pub sent: usize, pub failed: usize }`
  - `pub async fn crank_once(chain: &dyn CrankChain, program: &Key, signer: &SigningKey, now: i64) -> Result<CrankReport>`
  - `pub async fn run(chain: Arc<RpcChain>, program: Key, signer: SigningKey)`
  - `Settings.crank: Option<SigningKey>` (variable `CRANK_KEYPAIR`, même format de fichier que `PUBLICATION_AUTHORITY_KEYPAIR`)

- [ ] **Step 1 : Tests qui échouent**

Dans `crank.rs`, `mod tests` :

```rust
    use super::*;
    use crate::chain::{Config, Profile};
    use std::sync::Mutex;

    #[test]
    fn runs_after_midnight_then_after_closure() {
        let day = 100 * 86_400;
        assert_eq!(next_run(day, 21_600), day + 300);
        assert_eq!(next_run(day + 300, 21_600), day + 21_600 + 300);
        assert_eq!(next_run(day + 21_600 + 300, 21_600), day + 86_400 + 300);
    }

    #[test]
    fn reap_transaction_is_a_signed_legacy_message() {
        let program = [9; 32];
        let caller = SigningKey::from_bytes(&[3; 32]);
        let owner = [4; 32];
        let raw = reap_transaction(&program, &caller, &owner, 100, &[(98, false), (99, true)], &[6; 32]);
        assert_eq!(raw[0], 1);
        let message = &raw[65..];
        crate::protocol::verify(&caller.verifying_key().to_bytes(), message, &raw[1..65]).unwrap();
        // 1 signataire, 0 en lecture signé, owner + config + system + pool 98 + programme en lecture.
        assert_eq!(&message[..4], &[1, 0, 5, 9]);
        let key = |i: usize| &message[4 + 32 * i..4 + 32 * (i + 1)];
        assert_eq!(key(0), caller.verifying_key().as_bytes());
        assert_eq!(key(1), &crate::chain::pda(&program, &[b"profile", &owner]).0);
        assert_eq!(key(2), &crate::chain::pda(&program, &[b"day_pool", &100i64.to_le_bytes()]).0);
        assert_eq!(key(3), &crate::chain::pda(&program, &[b"day_pool", &99i64.to_le_bytes()]).0);
        assert_eq!(key(8), &program);
        let ix = &message[4 + 32 * 9 + 32..];
        assert_eq!(ix[0], 1, "une instruction");
        assert_eq!(ix[1], 8, "index du programme");
        assert_eq!(&ix[2..11], &[8, 0, 4, 5, 1, 2, 6, 7, 3]);
        assert_eq!(ix[11], 16);
        assert_eq!(&ix[12..20], &crate::chain::discriminator("global:reap"));
        assert_eq!(&ix[20..28], &100i64.to_le_bytes());
    }

    struct Fake { profiles: Vec<Profile>, sent: Mutex<Vec<Vec<u8>>> }
    #[async_trait::async_trait]
    impl CrankChain for Fake {
        async fn config(&self) -> Result<Config> {
            Ok(Config { authority: [0; 32], min_stake: 1, decay_bps: 1000, max_decay_days: 30, pool_close_delay: 21_600 })
        }
        async fn profiles(&self) -> Result<Vec<Profile>> { Ok(self.profiles.clone()) }
        async fn latest_blockhash(&self) -> Result<Key> { Ok([6; 32]) }
        async fn send_transaction(&self, raw: &[u8]) -> Result<()> {
            self.sent.lock().unwrap().push(raw.to_vec());
            Ok(())
        }
    }

    #[tokio::test]
    async fn only_profiles_with_something_to_reap_get_a_transaction() {
        let profile = |owner: u8, settled_day: i64, pending: i64, active: bool| Profile {
            owner: [owner; 32], staked: 100, settled_day, exit_unlock_at: 0, active,
            pending_days: [pending, -1], pending_stakes: [100, 0],
        };
        let now = 101 * 86_400 + 300; // jour 101, 00:05
        let chain = Fake {
            profiles: vec![
                profile(1, 99, 99, true),   // jour 100 manqué
                profile(2, 100, 100, true), // à jour, créance de 100 encore ouverte
                profile(3, 99, -1, false),  // position fermée
            ],
            sent: Mutex::new(vec![]),
        };
        let report = crank_once(&chain, &[9; 32], &SigningKey::from_bytes(&[3; 32]), now).await.unwrap();
        assert_eq!((report.sent, report.failed), (1, 0));
    }
```

Les indices attendus viennent de l'ordre des clés : `[caller, profile, today_pool(100), pool 99 (w), owner, config, system, pool 98 (r), program]`. Les comptes de l'instruction suivent la structure `Reap` : `caller=0, owner=4, config=5, profile=1, day_pool=2, system=6`, puis les pools dans l'ordre fourni : `98 → 7`, `99 → 3`.

- [ ] **Step 2 : Vérifier l'échec**

Run : `cd keyserver && cargo test --lib crank`
Attendu : échec de compilation (module absent).

- [ ] **Step 3 : Implémenter `crank.rs`**

```rust
//! Daily crank: settles late profiles and pays out closed pools.
//! Permissionless on-chain; this server only pays the fees.
use crate::{
    chain::{self, Config, Profile, RpcChain, DAY},
    error::Result,
    protocol::Key,
};
use async_trait::async_trait;
use ed25519_dalek::{Signer, SigningKey};
use std::sync::Arc;

/// Five minutes after midnight (late profiles), five minutes after closure (payouts).
const MARGIN: i64 = 300;

#[async_trait]
pub trait CrankChain: Send + Sync {
    async fn config(&self) -> Result<Config>;
    async fn profiles(&self) -> Result<Vec<Profile>>;
    async fn latest_blockhash(&self) -> Result<Key>;
    async fn send_transaction(&self, raw: &[u8]) -> Result<()>;
}

pub fn next_run(now: i64, close_delay: i64) -> i64 {
    let today = now.div_euclid(DAY);
    [today, today + 1]
        .iter()
        .flat_map(|day| [day * DAY + MARGIN, day * DAY + close_delay + MARGIN])
        .filter(|at| *at > now)
        .min()
        .expect("tomorrow is always ahead")
}

pub fn reap_transaction(
    program: &Key,
    caller: &SigningKey,
    owner: &Key,
    day: i64,
    pools: &[(i64, bool)],
    blockhash: &Key,
) -> Vec<u8> {
    let payer = caller.verifying_key().to_bytes();
    let pool = |day: i64| chain::pda(program, &[b"day_pool", &day.to_le_bytes()]).0;
    let config = chain::pda(program, &[b"config"]).0;
    let profile = chain::pda(program, &[b"profile", owner]).0;
    let today_pool = pool(day);
    let mut writable = vec![profile, today_pool];
    writable.extend(pools.iter().filter(|(_, w)| *w).map(|(d, _)| pool(*d)));
    let mut readonly = vec![*owner, config, [0; 32]];
    readonly.extend(pools.iter().filter(|(_, w)| !*w).map(|(d, _)| pool(*d)));
    readonly.push(*program);
    let keys: Vec<Key> = std::iter::once(payer).chain(writable).chain(readonly.iter().copied()).collect();
    let index = |key: &Key| keys.iter().position(|k| k == key).expect("key listed") as u8;

    let mut accounts = vec![index(&payer), index(owner), index(&config), index(&profile), index(&today_pool), index(&[0; 32])];
    accounts.extend(pools.iter().map(|(d, _)| index(&pool(*d))));
    let mut data = chain::discriminator("global:reap").to_vec();
    data.extend_from_slice(&day.to_le_bytes());

    // Every length stays below 128: compact-u16 is a single byte.
    let mut message = vec![1, 0, readonly.len() as u8, keys.len() as u8];
    keys.iter().for_each(|k| message.extend_from_slice(k));
    message.extend_from_slice(blockhash);
    message.extend_from_slice(&[1, index(program), accounts.len() as u8]);
    message.extend_from_slice(&accounts);
    message.push(data.len() as u8);
    message.extend_from_slice(&data);
    let mut raw = vec![1];
    raw.extend_from_slice(&caller.sign(&message).to_bytes());
    raw.extend_from_slice(&message);
    raw
}

#[derive(Debug, Default)]
pub struct CrankReport {
    pub sent: usize,
    pub failed: usize,
}

/// Idempotent: a profile with nothing to reap is skipped, and a failed send
/// is retried at the next run.
pub async fn crank_once(chain: &dyn CrankChain, program: &Key, signer: &SigningKey, now: i64) -> Result<CrankReport> {
    let config = chain.config().await?;
    let today = now.div_euclid(DAY);
    let payer = signer.verifying_key().to_bytes();
    let mut report = CrankReport::default();
    let mut blockhash = chain.latest_blockhash().await?;
    for (n, profile) in chain.profiles().await?.into_iter().enumerate() {
        if profile.owner == payer || !profile.needs_reap(&config, now) {
            continue;
        }
        if n > 0 && n % 100 == 0 {
            blockhash = chain.latest_blockhash().await?;
        }
        let pools = profile.settlement_pools(&config, now, profile.settle_bound(today));
        let raw = reap_transaction(program, signer, &profile.owner, today, &pools, &blockhash);
        match chain.send_transaction(&raw).await {
            Ok(()) => report.sent += 1,
            Err(_) => report.failed += 1,
        }
    }
    Ok(report)
}

fn unix_now() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0)
}

pub async fn run(chain: Arc<RpcChain>, program: Key, signer: SigningKey) {
    loop {
        let delay = match CrankChain::config(chain.as_ref()).await {
            Ok(config) => config.pool_close_delay,
            Err(_) => 21_600,
        };
        let now = unix_now();
        let wait = (next_run(now, delay) - now).max(1) as u64;
        tokio::time::sleep(std::time::Duration::from_secs(wait)).await;
        match crank_once(chain.as_ref(), &program, &signer, unix_now()).await {
            Ok(report) => tracing::info!(sent = report.sent, failed = report.failed, "crank reap"),
            Err(_) => tracing::warn!("crank reap: chain unavailable"),
        }
    }
}
```

Dans `chain.rs` : `rpc()` délègue à un `rpc_limited(method, params, max_bytes)` (32 768 octets par défaut, 8 Mo pour `getProgramAccounts`). `impl CrankChain for RpcChain` :
- `config` : délègue à `Chain::config`.
- `profiles` : `getProgramAccounts` avec `{"encoding":"base64","commitment":"confirmed","filters":[{"dataSize":127},{"memcmp":{"offset":0,"bytes":<base58 de discriminator("account:Profile")>}}]}`. Pour chaque entrée, décoder `account.data[0]` puis `decode_profile` ; une entrée illisible est ignorée, pas fatale.
- `latest_blockhash` : `getLatestBlockhash` `[{"commitment":"confirmed"}]` → `value.blockhash` → `protocol::address`.
- `send_transaction` : `sendTransaction` `[protocol::b64(raw), {"encoding":"base64","preflightCommitment":"confirmed"}]`.

`config.rs` : extraire la lecture de clé en `fn load_keypair(path: &str) -> anyhow::Result<SigningKey>` (réutilisée pour l'autorité), puis `let crank = env::var("CRANK_KEYPAIR").ok().filter(|v| !v.trim().is_empty()).map(|p| load_keypair(&p)).transpose()?;`. Refuser `CRANK_KEYPAIR` égal à la clé d'autorité : on garde la clé de co-signature hors du chemin des frais.

`main.rs` : avant de construire `App`, `let crank_chain = chain.clone();` puis, après `App` :

```rust
    match settings.crank {
        Some(signer) => {
            tokio::spawn(moment_keyserver::crank::run(crank_chain, settings.program, signer));
            tracing::info!("Crank reap actif (00:05 et clôture + 5 min UTC)");
        }
        None => tracing::info!("Crank reap désactivé : CRANK_KEYPAIR absent"),
    }
```

- [ ] **Step 4 : Vérifier le succès**

Run : `cd keyserver && cargo test --lib && cargo clippy --all-targets -- -D warnings`
Attendu : PASS, aucun avertissement.

- [ ] **Step 5 : Commit**

```bash
git add keyserver/src
git commit -m "feat(keyserver): crank reap quotidien"
```

---

### Task 6 : App — comptes, pools et transactions

**Files:**
- Modify: `app/app/src/main/java/com/clockin/hackathon/chain/ClockInAccounts.kt`, `ClockInInstructions.kt`
- Create: `app/app/src/main/java/com/clockin/hackathon/chain/DailyPool.kt`
- Modify: `app/app/src/main/java/com/clockin/hackathon/ClockInModel.kt`
- Test: `app/app/src/test/java/com/clockin/hackathon/chain/{AccountsTest,InstructionsTest}.kt`, `ChainStateTest.kt`
- Delete: `app/app/src/test/java/com/clockin/hackathon/chain/DevnetConfigTest.kt` (remplacé par `LayoutFixtureTest.kt`)

**Interfaces:**
- Consumes : fixture `keyserver/tests/fixtures/account-layouts-v2.hex` (Task 2), `ClockInAddresses.dayPool` (Task 4).
- Produces :
  - `ConfigAccount(admin, publicationAuthority, skrMint, vault, minStake, faucetAmount, withdrawalDelaySeconds, poolCloseDelaySeconds: Long, decayBps, maxDecayDays, faucetEnabled)`
  - `ProfileAccount(…champs actuels…, pendingDays: List<Long> = listOf(-1, -1), pendingStakes: List<Long> = listOf(0, 0))`, `settledBalance(decayBps, maxDecayDays, today, gain: Long = 0)`
  - `DayPoolAccount(day: Long, penalties: Long, totalStake: Long, winnersCount: Long)`, `ClockInAccounts.decodeDayPool(data)`
  - `object DailyPool { fun closesAt(day: Long, delay: Long): Long; fun share(penalties: Long, stake: Long, totalStake: Long): Long; fun settleBound(profile: ProfileAccount, today: Long): Long; fun poolMetas(programId, profile: ProfileAccount?, config: ConfigAccount?, now: Long, bound: Long): List<AccountMeta>; fun poolDays(profile: ProfileAccount?, today: Long): Set<Long> }`
  - `ChainState` gagne `pools: Map<Long, DayPoolAccount> = emptyMap()` et `now: Long = 0`, et expose `closedGain`, `pendingGain`, `payoutAt: Long?`, `todayPoolTotal`, `myShareToday`. `dailyReward` disparaît.
  - `ClockInInstructions.stake(programId, owner, mint, ownerTokenAccount, day, amount, pools)`, `requestExit(programId, owner, day, pools)`, `cancelExit(programId, owner)`, `finalizeExit(programId, caller, owner, mint, ownerTokenAccount, day, pools)`.

- [ ] **Step 1 : Tests qui échouent**

Créer `LayoutFixtureTest.kt` :

```kotlin
package com.clockin.hackathon.chain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Octets écrits par le programme Rust (tests/test_layout.rs) : ce test tombe
 * si le décodeur Kotlin diverge de la disposition Borsh. */
class LayoutFixtureTest {
    private val lines = File("../../keyserver/tests/fixtures/account-layouts-v2.hex").readLines()
        .associate { it.substringBefore('=') to it.substringAfter('=') }
    private fun bytes(name: String) = lines.getValue(name).chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun decodes_config() {
        val config = ClockInAccounts.decodeConfig(bytes("config"))
        assertEquals(500_000_000_000L, config.minStake)
        assertEquals(1_000_000_000_000L, config.faucetAmount)
        assertEquals(172_800L, config.withdrawalDelaySeconds)
        assertEquals(21_600L, config.poolCloseDelaySeconds)
        assertEquals(1000, config.decayBps)
        assertEquals(30, config.maxDecayDays)
        assertTrue(config.faucetEnabled)
    }

    @Test fun decodes_profile_claims() {
        val profile = ClockInAccounts.decodeProfile(bytes("profile"))
        assertEquals(123_000_000_000L, profile.staked)
        assertEquals(listOf(20_717L, 20_718L), profile.pendingDays)
        assertEquals(listOf(100_000_000_000L, 110_000_000_000L), profile.pendingStakes)
    }

    @Test fun decodes_day_pool() {
        val pool = ClockInAccounts.decodeDayPool(bytes("day_pool"))
        assertEquals(DayPoolAccount(20_718, 30_000_000_000, 600_000_000_000, 6), pool)
    }
}
```

Réécrire `ChainStateTest.kt` :

```kotlin
class ChainStateTest {
    private val today = 20_706L
    private val delay = 21_600L
    private val morning = today * 86_400 + 3 * 3_600   // 03:00, pool d'hier ouvert
    private val noon = today * 86_400 + 12 * 3_600     // 12:00, pool d'hier clôturé

    private fun config() = ConfigAccount(
        admin = ByteArray(32), publicationAuthority = ByteArray(32), skrMint = ByteArray(32),
        vault = ByteArray(32), minStake = 500 * SKR, faucetAmount = 100 * SKR,
        withdrawalDelaySeconds = 172_800L, poolCloseDelaySeconds = delay,
        decayBps = 1_000, maxDecayDays = 30, faucetEnabled = true,
    )

    private fun profile(pendingDays: List<Long>, pendingStakes: List<Long>) = ProfileAccount(
        owner = ByteArray(32), staked = 500 * SKR, settledDay = today - 1, lastCheckInDay = today - 1,
        exitRequestedAt = 0, exitUnlockAt = 0, totalCheckIns = 4, streak = 4,
        active = true, faucetClaimed = true, pendingDays = pendingDays, pendingStakes = pendingStakes,
    )

    private val yesterdayPool = DayPoolAccount(today - 1, penalties = 30 * SKR, totalStake = 1_500 * SKR, winnersCount = 3)

    private fun state(now: Long, pools: Map<Long, DayPoolAccount> = mapOf(today - 1 to yesterdayPool)) = ChainState(
        config = config(), profile = profile(listOf(today - 1, -1), listOf(500 * SKR, 0)),
        day = today, pools = pools, now = now,
    )

    @Test fun `an open claim is pending, not in the balance`() {
        val s = state(morning)
        assertEquals(10 * SKR, s.pendingGain)   // 30 × 500 / 1 500
        assertEquals(500 * SKR, s.balance)
        assertEquals((today) * 86_400 + delay, s.payoutAt)
    }

    @Test fun `a closed claim lands in the balance`() {
        val s = state(noon)
        assertEquals(0L, s.pendingGain)
        assertEquals(510 * SKR, s.balance)
        assertEquals(null, s.payoutAt)
    }

    @Test fun `a missing pool promises nothing`() {
        assertEquals(0L, state(morning, pools = emptyMap()).pendingGain)
    }

    @Test fun `today's share if you post includes your own stake in the total`() {
        val pools = mapOf(today to DayPoolAccount(today, penalties = 10 * SKR, totalStake = 500 * SKR, winnersCount = 1))
        val s = ChainState(config = config(), profile = profile(listOf(-1, -1), listOf(0, 0)), day = today, pools = pools, now = noon)
        assertEquals(10 * SKR, s.todayPoolTotal)
        assertEquals(5 * SKR, s.myShareToday)  // 10 × 500 / (500 + 500)
    }
}
```

`InstructionsTest.kt` : ajouter un test par builder modifié (`stake`, `requestExit`, `cancelExit`, `finalizeExit`), qui vérifie la position du `dayPool(day)` en écriture, `config` en lecture et les `pools` en fin de liste, dans l'ordre des structures Rust de la Task 2. Ajouter aussi :

```kotlin
    @Test fun poolMetasFollowTheProgramRule() {
        val program = SolanaPublicKey(ByteArray(32) { 3 })
        val config = /* config() de ChainStateTest, delay = 21 600 */
        val profile = /* publié le 98, absent le 99 : settledDay = 98, pendingDays = [98, -1], active */
        val now = 100 * 86_400L + 3 * 3_600
        val metas = DailyPool.poolMetas(program, profile, config, now, bound = 99)
        assertEquals(listOf(ClockInAddresses.dayPool(program, 98), ClockInAddresses.dayPool(program, 99)), metas.map { it.publicKey })
        assertEquals(listOf(false, true), metas.map { it.isWritable })
    }
```

(Remplir `config` et `profile` avec les constructeurs complets, comme dans `ChainStateTest`.) `AccountsTest.kt` : ajouter `pendingDays`/`pendingStakes` là où les tests fabriquent des octets de profil, et passer à `poolCloseDelaySeconds` pour la config.

- [ ] **Step 2 : Vérifier l'échec**

Run : `cd app && ./gradlew :app:testDebugUnitTest`
Attendu : échec de compilation (`poolCloseDelaySeconds`, `DayPoolAccount`, `DailyPool`, `pendingGain`…).

- [ ] **Step 3 : Implémenter les comptes et `DailyPool`**

`ClockInAccounts.kt` : `ConfigAccount` suit l'interface (plus de `poolBalance`, `rewardCap`, `rewardRateBps`) et `decodeConfig` lit dans l'ordre `admin, publicationAuthority, skrMint, vault, minStake(u64), faucetAmount(u64), withdrawalDelaySeconds(i64), poolCloseDelaySeconds(i64), decayBps(u16), maxDecayDays(u8), faucetEnabled(bool)`. `decodeProfile` lit ensuite `pendingDays = listOf(reader.i64(), reader.i64())` et `pendingStakes = listOf(reader.u64(), reader.u64())` après `faucetClaimed` et le bump (`reader.u8()` à ajouter : le bump précède les créances). Ajouter

```kotlin
data class DayPoolAccount(val day: Long, val penalties: Long, val totalStake: Long, val winnersCount: Long)

    fun decodeDayPool(data: ByteArray): DayPoolAccount {
        val reader = readerFor(data, "DayPool")
        return DayPoolAccount(day = reader.i64(), penalties = reader.u64(), totalStake = reader.u64(), winnersCount = reader.u32())
    }
```

`settledBalance(decayBps, maxDecayDays, today, gain: Long = 0)` : même calcul, en partant de `staked + gain` (le programme encaisse avant de pénaliser). Le `bound` se calcule avec `DailyPool.settleBound(this, today)`.

`DailyPool.kt` :

```kotlin
package com.clockin.hackathon.chain

import com.solana.publickey.SolanaPublicKey
import com.solana.transaction.AccountMeta

/** Miroir des règles du pool journalier du programme (`economy.rs`,
 * `settlement.rs`). Toute divergence se voit comme un échec de transaction,
 * jamais comme un paiement faux : la chaîne recalcule tout. */
object DailyPool {
    const val NO_DAY = -1L
    private const val DAY = 86_400L

    fun closesAt(day: Long, delay: Long): Long = (day + 1) * DAY + delay

    fun share(penalties: Long, stake: Long, totalStake: Long): Long {
        if (totalStake <= 0) return 0
        val raw = penalties.toBigInteger() * stake.toBigInteger() / totalStake.toBigInteger()
        return minOf(raw, penalties.toBigInteger()).toLong()
    }

    fun settleBound(profile: ProfileAccount, today: Long): Long =
        if (profile.exitUnlockAt > 0) minOf(today - 1, Math.floorDiv(profile.exitUnlockAt, DAY) - 1) else today - 1

    /** Comptes restants d'une instruction qui règle ce profil jusqu'à `bound`. */
    fun poolMetas(
        programId: SolanaPublicKey,
        profile: ProfileAccount?,
        config: ConfigAccount?,
        now: Long,
        bound: Long,
    ): List<AccountMeta> {
        if (profile == null || config == null) return emptyList()
        val delay = config.poolCloseDelaySeconds
        val claims = profile.pendingDays.filter { it != NO_DAY && now >= closesAt(it, delay) }
            .map { AccountMeta(ClockInAddresses.dayPool(programId, it), false, false) }
        val penalty = if (profile.active && bound > profile.settledDay && now < closesAt(bound, delay))
            listOf(AccountMeta(ClockInAddresses.dayPool(programId, bound), false, true)) else emptyList()
        return claims + penalty
    }

    /** Jours dont l'app lit le pool : créances, borne de règlement et aujourd'hui. */
    fun poolDays(profile: ProfileAccount?, today: Long): Set<Long> = buildSet {
        add(today)
        profile?.pendingDays?.filter { it != NO_DAY }?.let(::addAll)
        profile?.let { add(settleBound(it, today)) }
    }
}
```

- [ ] **Step 4 : `ChainState` et instructions**

Dans `ChainState` (`ClockInModel.kt`), ajouter `val pools: Map<Long, DayPoolAccount> = emptyMap()` et `val now: Long = 0` au constructeur, supprimer `dailyReward` et son commentaire, puis :

```kotlin
    private val delay: Long get() = config?.poolCloseDelaySeconds ?: 21_600

    private fun claims(closed: Boolean): List<Pair<Long, Long>> = profile?.let { p ->
        p.pendingDays.zip(p.pendingStakes).filter { (day, _) ->
            day != DailyPool.NO_DAY && (now >= DailyPool.closesAt(day, delay)) == closed
        }
    } ?: emptyList()

    private fun gain(claims: List<Pair<Long, Long>>): Long = claims.sumOf { (day, stake) ->
        pools[day]?.let { DailyPool.share(it.penalties, stake, it.totalStake) } ?: 0
    }

    /** Parts clôturées : elles sont à l'utilisateur, la prochaine instruction les encaisse. */
    val closedGain: Long get() = gain(claims(closed = true))

    /** « +X SKR en attente » : parts des pools pas encore clôturés, estimées sur leur état actuel. */
    val pendingGain: Long get() = gain(claims(closed = false))

    /** Heure du prochain versement, en secondes epoch. */
    val payoutAt: Long? get() = claims(closed = false).minOfOrNull { (day, _) -> DailyPool.closesAt(day, delay) }

    val todayPoolTotal: Long get() = pools[day]?.penalties ?: 0

    /** Part du pool d'aujourd'hui : acquise si l'on a publié, promise sinon. */
    val myShareToday: Long get() {
        val pool = pools[day] ?: return 0
        val mine = profile?.pendingDays?.indexOf(day)?.takeIf { it >= 0 }?.let { profile.pendingStakes[it] }
        return if (mine != null) DailyPool.share(pool.penalties, mine, pool.totalStake)
        else DailyPool.share(pool.penalties, balance, pool.totalStake + balance)
    }
```

`balance` devient `profile.settledBalance(config.decayBps, config.maxDecayDays, day, closedGain)`. Le repli `decayBps` passe de `2_500` à `1_000`.

`Profile.kt`, le minimum pour que le module compile (l'affichage complet est en Task 7) : `Pool()` prend `total = state.todayPoolTotal` et `mine = state.myShareToday`. `RulesContent` perd `reward`, `cap`, `DEFAULT_REWARD_BPS` et la ligne `Rule(tr(Message.EachConfirmedMomentCanEarnYouOf, …))`. `RulesDiagram(reward, decay)` devient `RulesDiagram(decay)`, avec `"+SKR"` dans la branche « publié ».

`refreshNow` : après `profile`, lire les pools puis construire l'état.

```kotlin
        val pools = DailyPool.poolDays(profile, day).mapNotNull { d ->
            rpc.accountData(ClockInAddresses.dayPool(programId, d), programId)
                ?.let(ClockInAccounts::decodeDayPool)?.let { d to it }
        }.toMap()
        …
        state = ChainState(config, profile, checkIn, balance, day, loaded = true, pools = pools, now = System.currentTimeMillis() / 1000)
```

`ClockInInstructions` : pour `stake`, `requestExit`, `cancelExit` et `finalizeExit`, reprendre exactement l'ordre des comptes de la Task 2. `config` passe en lecture, `dayPool(programId, day)` s'ajoute en écriture après `profile`, `SYSTEM_PROGRAM` s'ajoute en dernier compte nommé, `+ pools` en fin, et les données deviennent `discriminator + i64(day) [+ u64(amount)]`. `requestExit` : `owner` devient `AccountMeta(owner, true, true)` (payeur). `finalizeExit` : `caller` reste `(true, true)`. `cancelExit` garde `owner(s)`, `config(ro)`, `profile(w)`, sans argument.

`ClockInModel` : ajouter

```kotlin
    private fun settlementPools(owner: SolanaPublicKey, bound: Long): List<AccountMeta> =
        DailyPool.poolMetas(programId, state.profile, state.config, System.currentTimeMillis() / 1000, bound)
```

et l'utiliser dans `stake` (`bound = DailyPool.settleBound(profile, utcDay())`, liste vide si le profil est inactif), `requestExit`, `finalizeExit` (`bound = Math.floorDiv(exitUnlockAt, 86_400) - 1`) et `publish` (`checkIn(..., pools = settlementPools(owner, DailyPool.settleBound(profile, day)))`), avec `day = utcDay()` passé à chaque builder.

- [ ] **Step 5 : Vérifier le succès**

Run : `cd app && ./gradlew :app:testDebugUnitTest`
Attendu : PASS. (Les tests androidTest ne compilent plus tant que `ProfileScreenTest`/`OnboardingTest` construisent l'ancienne `ConfigAccount` : c'est la Task 7.)

- [ ] **Step 6 : Commit**

```bash
git add app/app/src
git commit -m "feat(app): lecture des pools journaliers et nouvelles transactions"
```

---

### Task 7 : App — affichage du gain en attente, fin de la récompense immédiate

**Files:**
- Modify: `app/app/src/main/java/com/clockin/hackathon/ui/Profile.kt`, `ui/PublicationTime.kt`, `i18n/Message.kt`
- Modify: `app/app/src/androidTest/java/com/clockin/hackathon/ui/{ProfileScreenTest,OnboardingTest}.kt`

**Interfaces:**
- Consumes : `ChainState.pendingGain`, `payoutAt`, `todayPoolTotal`, `myShareToday` (Task 6).
- Produces : `fun clockTime(epochSeconds: Long, zone: ZoneId = ZoneId.systemDefault()): String` (`HH:mm`) dans `PublicationTime.kt`, et les messages `PendingGain`, `PaidAt`, `EachPublishedMomentSharesTheDayPool`.

- [ ] **Step 1 : Test UI qui échoue**

Dans `ProfileScreenTest.kt`, construire la `ConfigAccount` avec les nouveaux champs (comme dans `ChainStateTest`) et ajouter un test qui affiche le profil avec un `ChainState` à créance ouverte (`pendingDays = [today - 1, -1]`, pool de la veille à 30 SKR pour 1 500 SKR de mises, `now` à 03:00 UTC). Il vérifie `onNodeWithText("+10 SKR en attente", substring = true).assertExists()` (texte FR : le test fixe la langue comme les autres tests du fichier) et l'absence de tout texte contenant `jusqu’à` (ancienne règle du plafond). Adapter la construction de `ConfigAccount` dans `OnboardingTest.kt`.

- [ ] **Step 2 : Vérifier l'échec**

Run : `cd app && ./gradlew :app:compileDebugAndroidTestKotlin`
Attendu : échec de compilation tant que `Profile.kt` utilise `dailyReward`, `rewardRateBps` et `rewardCap`. Puis, sur appareil : `./gradlew :app:connectedDebugAndroidTest --tests '*ProfileScreenTest*'` → échec sur le texte absent (voir la mémoire « captures d'écran androidTest » si besoin de captures).

- [ ] **Step 3 : Implémenter**

`Message.kt` : supprimer `EachConfirmedMomentCanEarnYouOf`, ajouter

```kotlin
    EachPublishedMomentSharesTheDayPool("Chaque Moment publié te donne une part du pool du jour, au prorata de ton staking. Elle arrive dans ton solde le lendemain à {0}.", "Each published Moment earns you a share of the day's pool, in proportion to your stake. It lands in your balance the next day at {0}."),
    PendingGain("+{0}{1}SKR en attente", "+{0}{1}SKR pending"),
    PaidAt("Versé à {0}", "Paid at {0}"),
```

Puis `grep -n "récompense\|rapporte\|reward\|Reward" app/app/src/main/java/com/clockin/hackathon/i18n/Message.kt app/app/src/main/java/com/clockin/hackathon/ui/*.kt`. Toute mention restante d'un gain *immédiat* au check-in est réécrite pour parler de la part du pool versée le lendemain. Les mentions de « part » et de « pool » restent.

`PublicationTime.kt` :

```kotlin
/** Heure locale `HH:mm` d'un instant epoch (secondes). */
fun clockTime(epochSeconds: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    java.time.Instant.ofEpochSecond(epochSeconds).atZone(zone).toLocalTime()
        .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
```

`Profile.kt` :
- Juste après l'appel `Pool(state, demoFeed)` (≈ ligne 120), ajouter `PendingGain(state)` :

```kotlin
/** Part des pools pas encore clôturés : elle arrive dans le solde au versement. */
@Composable
private fun PendingGain(state: ChainState) {
    val at = state.payoutAt ?: return
    if (state.pendingGain <= 0) return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(tr(Message.PendingGain, skr(state.pendingGain), NB), style = LabelLg, color = Success)
        Text(tr(Message.PaidAt, clockTime(at)), style = LabelLg, color = Muted)
    }
}
```

- `RulesContent` : ajouter, en tête des règles, `Rule(tr(Message.EachPublishedMomentSharesTheDayPool, clockTime((state.day + 1) * 86_400 + (config?.poolCloseDelaySeconds ?: 21_600))))`.

- [ ] **Step 4 : Vérifier le succès**

Run : `cd app && ./gradlew :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin`
Attendu : PASS. Sur appareil : `./gradlew :app:connectedDebugAndroidTest` → PASS (`ProfileScreenTest`, `OnboardingTest`, `LanguageTest`).

- [ ] **Step 5 : Commit**

```bash
git add app/app/src
git commit -m "feat(app): gain en attente et règles du pool journalier"
```

---

### Task 8 : Bootstrap devnet et documentation

**Files:**
- Modify: `program/scripts/bootstrap-devnet.ts`, `docs/devnet-run.md`, `README.md` (section économie, si elle décrit la récompense immédiate)

**Interfaces:**
- Consumes : `ConfigParams` et `seed_pool(day, amount)` (Task 2).

- [ ] **Step 1 : Script**

Remplacer `params` par :

```ts
/** Paramètres de travail (spec pool journalier). */
const params = Buffer.concat([
  u64(500n * SKR), // min_stake
  u64(1000n * SKR), // faucet_amount
  i64(172_800n), // withdrawal_delay_seconds : 48 h
  i64(21_600n), // pool_close_delay_seconds : pool de D clôturé à D+1 06:00 UTC
  u16(1000), // decay_bps : 10 % par jour manqué
  Buffer.from([30]), // max_decay_days
  Buffer.from([1]), // faucet_enabled
]);
```

`seedPool` : `const day = BigInt(Math.floor(Date.now() / 1000 / 86_400));`, `dayPool = PublicKey.findProgramAddressSync([Buffer.from("day_pool"), i64(day)], programId)[0]`, comptes `admin(s,w)`, `config(ro)`, `dayPool(w)`, `mint`, `adminTokenAccount(w)`, `vault(w)`, `TOKEN_PROGRAM`, `SystemProgram.programId`, données `discriminator("seed_pool") + i64(day) + u64(seedAmount)`. Mettre à jour le commentaire d'en-tête : l'amorçage va aux publieurs du jour du bootstrap, et il reste bloqué si personne ne publie ce jour-là.

Run : `cd program/scripts && npx tsc --noEmit bootstrap-devnet.ts` (ou le contrôle de type déjà utilisé par `package.json`). Attendu : aucune erreur.

- [ ] **Step 2 : Documentation**

`docs/devnet-run.md` :
- Procédure de réinitialisation. Nouveau program id (`solana-keygen new -o target/deploy/clockin-keypair.json` puis `anchor keys sync`) ou fermeture de l'ancien programme, car les comptes `Config`/`Profile` existants ont l'ancienne taille. Puis `deploy-devnet.sh`, puis bootstrap.
- Variable `CRANK_KEYPAIR` du keyserver : clé distincte de l'autorité de publication, approvisionnée en SOL (frais et loyer des `DayPool` créés par `reap`).
- Vérification manuelle de bout en bout (substitut au test contre validateur local, écart 5). Deux wallets publient le jour D, un troisième reste absent. Le lendemain après 00:05 UTC, les logs du keyserver montrent `crank reap sent=1`. Après 06:05 UTC, `sent=2`, et le profil de chaque publieur affiche la part créditée.

`README.md` : remplacer toute description de la récompense au check-in par le pool journalier (une phrase et un renvoi vers la spec).

- [ ] **Step 3 : Commit**

```bash
git add program/scripts/bootstrap-devnet.ts docs/devnet-run.md README.md
git commit -m "docs: bootstrap et runbook devnet du pool journalier"
```

- [ ] **Step 4 : Redéploiement devnet (avec accord explicite de l'utilisateur)**

Déployer, lancer le bootstrap, mettre à jour `BuildConfig.PROGRAM_ID`/`RPC` si le program id change, puis dérouler la vérification manuelle du Step 2. **Action sortante : demander confirmation avant de la lancer.**
