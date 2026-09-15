# Programme Anchor `clockin` — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remplacer le scaffold compteur par le programme métier complet — mise SKR dans un vault unique, check-in quotidien avec commitment, decay des jours manqués, redistribution, `reap` permissionless et sortie différée de 48 h — développé en TDD sous litesvm.

**Architecture:** Un seul vault SPL détient tout le SKR ; le decay et les récompenses sont de la comptabilité dans `Config.pool_balance` et `Profile.staked`, sans transfert de tokens. Seuls `stake`, `seed_pool`, `faucet` et `finalize_exit` déplacent réellement des tokens. Toute la logique de calcul vit dans un module `economy` pur, testable sans machine virtuelle ; les instructions n'orchestrent que les comptes et les CPI.

**Tech Stack:** Rust 1.89.0, anchor-lang 1.2.0, anchor-spl 1.2.0, litesvm 0.10.0, litesvm-token 0.10.0, solana-cli 4.1.2.

**Spec:** [`docs/superpowers/specs/2026-09-14-clock-in-design.md`](../specs/2026-09-14-clock-in-design.md) §5, §6, §10, §11. Décisions D1, D3, D4, D6, D7 de la [feuille de route](2026-09-15-00-feuille-de-route.md).

## Global Constraints

- Le jour est dérivé de `Clock` : `unix_timestamp.div_euclid(86_400)`. Quand un `day` est passé en argument (PDA `CheckIn`), il est **vérifié** contre l'horloge, jamais cru (D6).
- `withdrawal_delay_seconds` = `172_800` en v1, figé à la demande de sortie même si la configuration change ensuite.
- Valeurs de travail : `min_stake` = 10 SKR, `reward_rate_bps` = 100, `reward_cap` = 1 SKR, `decay_bps` = 2500, `max_decay_days` = 30, `faucet_amount` = 100 SKR. Mint de test à **9 décimales** : `1 SKR = 1_000_000_000` unités.
- Un seul vault SPL contient les mises de tous les utilisateurs **et** le pool. Invariant permanent : `Σ profile.staked + config.pool_balance == solde du vault`.
- `decay` et `reward` calculent en `u128` puis repassent en `u64` ; aucun arrondi ne crée de tokens.
- Aucun bonus n'est versé à l'appelant de `reap` en v1 (§6.4, phase 3).
- Aucune sanction de modération on-chain en v1 (D3) : pas de compte `ModerationDay`, pas de débit de 10 %.
- `check_in` exige la signature de `Config.publication_authority` (D4).
- `declare_id!` reste `7TgCk9XekpU88Tiewd5VKfhmVJQyxNRR8915pzqU3rG1` : l'identifiant du programme ne change pas.
- Messages `#[msg(...)]` en français, comme le reste du produit.
- Les tests litesvm chargent `target/deploy/clockin.so` : **`anchor build --arch v0` avant `cargo test`**.
- **L'architecture SBPF est épinglée à v0.** `anchor build` cible v3 par défaut, mais le runtime agave 3.1 embarqué dans litesvm 0.10 ne charge pas les ELF v3 : `add_program` échoue alors sur un `InvalidAccountData` sans explication. Épingler v0 des deux côtés garantit que les tests exécutent exactement le binaire déployé. Voir `docs/decisions/2026-09-15-architecture-sbpf.md`.

## File Structure

| Fichier | Responsabilité |
|---|---|
| `program/programs/clockin/src/lib.rs` | `declare_id!`, module `#[program]`, une fonction par instruction qui délègue au handler. |
| `program/programs/clockin/src/constants.rs` | Graines de PDA et constantes de durée. |
| `program/programs/clockin/src/error.rs` | `ClockInError`, un variant par refus métier. |
| `program/programs/clockin/src/state.rs` | `Config`, `Profile`, `CheckIn`, et le règlement des jours manqués (`Profile::settle_bound`, `Profile::settle_through`). |
| `program/programs/clockin/src/economy.rs` | Fonctions pures, sans dépendance aux comptes : `day_of`, `decay`, `reward`, `settle_bound`. Tests unitaires natifs. |
| `program/programs/clockin/src/instructions/mod.rs` | Déclaration et réexport des modules d'instruction. |
| `program/programs/clockin/src/instructions/initialize_config.rs` | `initialize_config`, `update_config`, `set_publication_authority`, `seed_pool`. |
| `program/programs/clockin/src/instructions/profile.rs` | `create_profile`, `faucet`. |
| `program/programs/clockin/src/instructions/stake.rs` | `stake`. |
| `program/programs/clockin/src/instructions/check_in.rs` | `check_in`. |
| `program/programs/clockin/src/instructions/reap.rs` | `reap`. |
| `program/programs/clockin/src/instructions/exit.rs` | `request_exit`, `cancel_exit`, `finalize_exit`. |
| `program/programs/clockin/tests/common/mod.rs` | Harness litesvm : déploiement, mint, config, utilisateurs, voyage temporel, lecture de comptes. |
| `program/programs/clockin/tests/test_config.rs` | Initialisation, mise à jour, amorçage du pool. |
| `program/programs/clockin/tests/test_stake.rs` | Dépôts, réouverture, refus pendant une sortie. |
| `program/programs/clockin/tests/test_check_in.rs` | Check-in, autorisation, double check-in, seuil minimum. |
| `program/programs/clockin/tests/test_decay.rs` | Decay 1/2/N jours, plafond, dépôt après absence. |
| `program/programs/clockin/tests/test_reap.rs` | `reap`, absence de double-decay, refus si à jour. |
| `program/programs/clockin/tests/test_exit.rs` | Demande, annulation, finalisation, bornes temporelles. |
| `program/programs/clockin/tests/test_conservation.rs` | Scénario multi-jours multi-utilisateurs et invariant du vault. |

Fichiers supprimés : `src/instructions.rs`, `src/instructions/initialize.rs`, `src/instructions/increment.rs`, `tests/test_initialize.rs`.

---

### Task 1 : Commiter l'existant et isoler l'économie pure

Le dépôt contient du travail non commité (`app/`, `program/`, `docs/moment-design.md`, `README.md`). On le fige d'abord pour que les diffs du plan soient lisibles, puis on écrit le module de calcul pur — c'est lui qui porte tous les cas limites de la spec et il se teste sans machine virtuelle, donc en quelques millisecondes.

**Files:**
- Create: `program/programs/clockin/src/economy.rs`
- Modify: `program/programs/clockin/src/lib.rs`
- Delete: `program/programs/clockin/src/constants.rs` (contenu compteur, réécrit tâche 2)

**Interfaces:**
- Produces : `day_of(i64) -> i64`, `struct Decayed { remaining: u64, lost: u64 }`, `decay(staked: u64, missed_days: i64, decay_bps: u16, max_decay_days: u8) -> Decayed`, `reward(staked: u64, reward_rate_bps: u16, reward_cap: u64, pool_balance: u64) -> u64`, `DAY_SECONDS: i64`, `BPS_DENOMINATOR: u64`.

- [ ] **Step 1 : Commiter l'état actuel**

```bash
cd /Users/clementlevoux/Dev/solana/clock_in
git add -A
git commit -m "chore: figer le lot capture locale avant le branchement on-chain"
```

- [ ] **Step 2 : Écrire les tests d'économie (ils ne compilent pas encore)**

Créer `program/programs/clockin/src/economy.rs` avec **uniquement** le bloc de tests :

```rust
#[cfg(test)]
mod tests {
    use super::*;

    const SKR: u64 = 1_000_000_000;

    #[test]
    fn day_of_divides_utc_days_and_handles_negatives() {
        assert_eq!(day_of(0), 0);
        assert_eq!(day_of(86_399), 0);
        assert_eq!(day_of(86_400), 1);
        assert_eq!(day_of(-1), -1);
    }

    #[test]
    fn no_missed_day_keeps_everything() {
        let out = decay(100 * SKR, 0, 2500, 30);
        assert_eq!(out.remaining, 100 * SKR);
        assert_eq!(out.lost, 0);
    }

    #[test]
    fn one_missed_day_at_25_percent() {
        let out = decay(100 * SKR, 1, 2500, 30);
        assert_eq!(out.remaining, 75 * SKR);
        assert_eq!(out.lost, 25 * SKR);
    }

    #[test]
    fn two_missed_days_compound() {
        let out = decay(100 * SKR, 2, 2500, 30);
        assert_eq!(out.remaining, 56_250_000_000);
        assert_eq!(out.lost, 43_750_000_000);
    }

    #[test]
    fn fifty_percent_variant_matches_the_spec_table() {
        assert_eq!(decay(100 * SKR, 1, 5000, 30).remaining, 50 * SKR);
        assert_eq!(decay(100 * SKR, 2, 5000, 30).remaining, 25 * SKR);
    }

    #[test]
    fn beyond_max_decay_days_everything_is_lost() {
        let out = decay(100 * SKR, 31, 2500, 30);
        assert_eq!(out.remaining, 0);
        assert_eq!(out.lost, 100 * SKR);
    }

    #[test]
    fn remaining_plus_lost_always_equals_the_input() {
        for missed in 0..40i64 {
            for staked in [0u64, 1, 7, 12_345_678, u64::MAX / 2] {
                let out = decay(staked, missed, 2500, 30);
                assert_eq!(out.remaining + out.lost, staked, "missed={missed} staked={staked}");
            }
        }
    }

    #[test]
    fn reward_is_a_percentage_of_the_stake() {
        assert_eq!(reward(100 * SKR, 100, 10 * SKR, 1000 * SKR), SKR);
    }

    #[test]
    fn reward_is_capped_by_reward_cap() {
        assert_eq!(reward(10_000 * SKR, 100, SKR, 1000 * SKR), SKR);
    }

    #[test]
    fn reward_is_capped_by_the_pool() {
        assert_eq!(reward(100 * SKR, 100, 10 * SKR, SKR / 2), SKR / 2);
    }

    #[test]
    fn empty_pool_pays_nothing() {
        assert_eq!(reward(100 * SKR, 100, 10 * SKR, 0), 0);
    }
}
```

- [ ] **Step 3 : Vérifier que la compilation échoue**

Run : `cd program && cargo test -p clockin --lib`
Expected : FAIL — `cannot find function 'day_of' in this scope`.

- [ ] **Step 4 : Écrire le module**

En tête de `program/programs/clockin/src/economy.rs`, avant le bloc de tests :

```rust
//! Calculs économiques purs (§6 de la spec).
//!
//! Aucune dépendance aux comptes Solana : ce module se teste sur l'hôte,
//! ce qui rend le TDD praticable sur la partie où les bugs coûtent le plus cher.

/// Longueur d'un jour UTC en secondes. Le jour est la seule unité de temps métier.
pub const DAY_SECONDS: i64 = 86_400;
/// Dénominateur des points de base.
pub const BPS_DENOMINATOR: u64 = 10_000;

/// Jour UTC contenant cet horodatage. `div_euclid` pour que les timestamps
/// négatifs (antérieurs à 1970, atteignables en test) restent monotones.
pub fn day_of(unix_timestamp: i64) -> i64 {
    unix_timestamp.div_euclid(DAY_SECONDS)
}

/// Résultat d'un decay : ce qui reste au profil, ce qui part au pool.
/// `remaining + lost == staked` par construction.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Decayed {
    pub remaining: u64,
    pub lost: u64,
}

/// Applique `decay_bps` par jour manqué. Au-delà de `max_decay_days`, le solde
/// tombe à zéro sans itérer : c'est la borne d'itération de §6.1.
pub fn decay(staked: u64, missed_days: i64, decay_bps: u16, max_decay_days: u8) -> Decayed {
    if missed_days <= 0 {
        return Decayed { remaining: staked, lost: 0 };
    }
    if missed_days > max_decay_days as i64 {
        return Decayed { remaining: 0, lost: staked };
    }
    let keep = (BPS_DENOMINATOR - decay_bps as u64) as u128;
    let denominator = BPS_DENOMINATOR as u128;
    let mut remaining = staked as u128;
    for _ in 0..missed_days {
        remaining = remaining * keep / denominator;
    }
    let remaining = remaining as u64;
    Decayed { remaining, lost: staked - remaining }
}

/// Récompense d'un check-in : pourcentage du solde, borné par le plafond
/// anti-baleine puis par ce que le pool peut réellement payer (§6.1).
pub fn reward(staked: u64, reward_rate_bps: u16, reward_cap: u64, pool_balance: u64) -> u64 {
    let raw = (staked as u128 * reward_rate_bps as u128 / BPS_DENOMINATOR as u128) as u64;
    raw.min(reward_cap).min(pool_balance)
}
```

Déclarer le module dans `program/programs/clockin/src/lib.rs` : ajouter `pub mod economy;` à côté des autres `pub mod`, et `pub use economy::*;`.

- [ ] **Step 5 : Vérifier que les tests passent**

Run : `cd program && cargo test -p clockin --lib`
Expected : PASS, 11 tests.

- [ ] **Step 6 : Commit**

```bash
git add program/programs/clockin/src/economy.rs program/programs/clockin/src/lib.rs
git commit -m "feat(program): module economy pur avec decay et reward testes"
```

---

### Task 2 : Comptes, configuration et harness litesvm

Cette tâche remplace entièrement le scaffold compteur : les trois comptes de §5, la
première instruction, et le harness de test dont dépendent toutes les tâches suivantes.

**Files:**
- Rewrite: `program/programs/clockin/src/constants.rs`, `src/error.rs`, `src/state.rs`, `src/lib.rs`
- Create: `program/programs/clockin/src/instructions/mod.rs`, `src/instructions/initialize_config.rs`
- Create: `program/programs/clockin/tests/common/mod.rs`, `tests/test_config.rs`
- Delete: `src/instructions.rs`, `src/instructions/initialize.rs`, `src/instructions/increment.rs`, `tests/test_initialize.rs`
- Modify: `program/programs/clockin/Cargo.toml`

**Interfaces:**
- Consumes : `economy::{day_of, DAY_SECONDS}` (tâche 1).
- Produces : comptes `Config`, `Profile`, `CheckIn` ; graines `CONFIG_SEED`, `VAULT_SEED`, `PROFILE_SEED`, `CHECKIN_SEED` ; `ConfigParams` ; instruction `initialize_config(params: ConfigParams)` ; harness `common::Ctx` avec `Ctx::new()`, `Ctx::today()`, `Ctx::warp_days(i64)`, `Ctx::config_state()`, `Ctx::vault_balance()`, `Ctx::send(&[Instruction], &[&Keypair])`.

- [ ] **Step 1 : Ajouter les dépendances**

Dans `program/programs/clockin/Cargo.toml` :

```toml
[dependencies]
anchor-lang = "1.2.0"
anchor-spl = "1.2.0"

[dev-dependencies]
litesvm = "0.10.0"
litesvm-token = "0.10.0"
solana-message = "3.0.1"
solana-transaction = "3.0.2"
solana-signer = "3.0.0"
solana-keypair = "3.0.1"
solana-clock = "3.0"
solana-instruction = "3.0"
```

- [ ] **Step 2 : Écrire le test de configuration**

`program/programs/clockin/tests/test_config.rs` :

```rust
mod common;

use common::{Ctx, MIN_STAKE, SKR};
use solana_keypair::Keypair;
use solana_signer::Signer;

#[test]
fn initialize_config_sets_parameters_and_creates_an_empty_vault() {
    let ctx = Ctx::new();
    let config = ctx.config_state();

    assert_eq!(config.admin, ctx.admin.pubkey());
    assert_eq!(config.publication_authority, ctx.authority.pubkey());
    assert_eq!(config.skr_mint, ctx.mint);
    assert_eq!(config.vault, ctx.vault);
    assert_eq!(config.pool_balance, 0);
    assert_eq!(config.min_stake, MIN_STAKE);
    assert_eq!(config.reward_rate_bps, 100);
    assert_eq!(config.reward_cap, SKR);
    assert_eq!(config.decay_bps, 2500);
    assert_eq!(config.max_decay_days, 30);
    assert_eq!(config.withdrawal_delay_seconds, 172_800);
    assert!(config.faucet_enabled);
    assert_eq!(ctx.vault_balance(), 0);
}

#[test]
fn initialize_config_refuses_an_impossible_decay_rate() {
    let mut ctx = Ctx::empty();
    let result = ctx.initialize_config_with(|params| params.decay_bps = 10_001);
    assert!(result.is_err(), "un decay > 100 % doit être refusé");
}
```

- [ ] **Step 3 : Vérifier l'échec**

Run : `cd program && cargo test --test test_config`
Expected : FAIL — le module `common` n'existe pas.

- [ ] **Step 4 : Écrire constantes, erreurs et comptes**

`program/programs/clockin/src/constants.rs` :

```rust
use anchor_lang::prelude::*;

#[constant]
pub const CONFIG_SEED: &[u8] = b"config";
#[constant]
pub const VAULT_SEED: &[u8] = b"vault";
#[constant]
pub const PROFILE_SEED: &[u8] = b"profile";
#[constant]
pub const CHECKIN_SEED: &[u8] = b"checkin";
```

`program/programs/clockin/src/error.rs` :

```rust
use anchor_lang::prelude::*;

#[error_code]
pub enum ClockInError {
    #[msg("Position inactive : dépose une mise avant d'utiliser cette fonction")]
    PositionInactive,
    #[msg("Solde effectif sous le minimum requis")]
    InsufficientStake,
    #[msg("Le jour fourni ne correspond pas au jour UTC courant")]
    DayMismatch,
    #[msg("Une demande de sortie est déjà en cours")]
    ExitAlreadyRequested,
    #[msg("Aucune demande de sortie en cours")]
    NoExitRequested,
    #[msg("La sortie n'est pas encore débloquée")]
    ExitLocked,
    #[msg("La sortie est débloquée : cette action n'est plus possible")]
    ExitUnlocked,
    #[msg("Dépôt impossible pendant une demande de sortie")]
    StakeDuringExit,
    #[msg("Aucun jour à régler sur ce profil")]
    NothingToReap,
    #[msg("Montant invalide")]
    InvalidAmount,
    #[msg("Faucet désactivé sur ce déploiement")]
    FaucetDisabled,
    #[msg("Faucet déjà utilisé par ce profil")]
    FaucetAlreadyClaimed,
    #[msg("Compte de tokens destinataire invalide")]
    InvalidOwnerTokenAccount,
    #[msg("Paramètre de configuration invalide")]
    InvalidConfigParam,
}
```

`program/programs/clockin/src/state.rs` :

```rust
use anchor_lang::prelude::*;

/// PDA singleton, graine `CONFIG_SEED`. Autorité du vault et porteur des
/// paramètres économiques, calibrables sans redéploiement (§15.2).
#[account]
#[derive(InitSpace)]
pub struct Config {
    pub admin: Pubkey,
    /// Clé qui co-signe `check_in` : autorisation de publication du backend (D4).
    pub publication_authority: Pubkey,
    pub skr_mint: Pubkey,
    pub vault: Pubkey,
    /// Part du vault qui appartient au pool de redistribution.
    pub pool_balance: u64,
    pub min_stake: u64,
    pub reward_cap: u64,
    pub faucet_amount: u64,
    pub withdrawal_delay_seconds: i64,
    pub reward_rate_bps: u16,
    pub decay_bps: u16,
    pub max_decay_days: u8,
    pub faucet_enabled: bool,
    pub bump: u8,
    pub vault_bump: u8,
}

/// PDA par wallet, graines `PROFILE_SEED || owner`.
#[account]
#[derive(InitSpace)]
pub struct Profile {
    pub owner: Pubkey,
    /// Solde unique : sert de mise ET de compteur de gains.
    pub staked: u64,
    /// Dernier jour dont la comptabilité est à jour : pilote le decay.
    pub settled_day: i64,
    /// Dernier jour réellement posté : affichage et streak.
    pub last_checkin_day: i64,
    pub exit_requested_at: i64,
    /// Figé à la demande, même si la configuration change ensuite (§6.3).
    pub exit_unlock_at: i64,
    pub total_checkins: u64,
    pub streak: u32,
    pub active: bool,
    pub faucet_claimed: bool,
    pub bump: u8,
}

/// PDA par couple wallet × jour : rend le double check-in structurellement
/// impossible, le compte existe déjà.
#[account]
#[derive(InitSpace)]
pub struct CheckIn {
    pub owner: Pubkey,
    pub day: i64,
    /// SHA-256 du manifeste canonique signé par le wallet (§7).
    pub commitment: [u8; 32],
    /// SHA-256 du blob chiffré stocké côté serveur (décision D1).
    pub blob_ref: [u8; 32],
    /// Slot d'inscription : l'ancre temporelle.
    pub slot: u64,
    pub streak_at_checkin: u32,
}
```

- [ ] **Step 5 : Écrire `initialize_config`**

`program/programs/clockin/src/instructions/mod.rs` :

```rust
pub mod initialize_config;

pub use initialize_config::*;
```

`program/programs/clockin/src/instructions/initialize_config.rs` :

```rust
use anchor_lang::prelude::*;
use anchor_spl::token::{Mint, Token, TokenAccount};

use crate::{constants::*, error::ClockInError, state::Config};

/// Paramètres économiques. Regroupés pour que l'ajout d'un paramètre ne change
/// pas la signature de l'instruction côté client.
#[derive(AnchorSerialize, AnchorDeserialize, Clone, Debug)]
pub struct ConfigParams {
    pub min_stake: u64,
    pub reward_cap: u64,
    pub faucet_amount: u64,
    pub withdrawal_delay_seconds: i64,
    pub reward_rate_bps: u16,
    pub decay_bps: u16,
    pub max_decay_days: u8,
    pub faucet_enabled: bool,
}

impl ConfigParams {
    pub fn validate(&self) -> Result<()> {
        require!(self.decay_bps <= 10_000, ClockInError::InvalidConfigParam);
        require!(self.reward_rate_bps <= 10_000, ClockInError::InvalidConfigParam);
        require!(self.max_decay_days >= 1, ClockInError::InvalidConfigParam);
        require!(self.withdrawal_delay_seconds >= 0, ClockInError::InvalidConfigParam);
        Ok(())
    }
}

#[derive(Accounts)]
pub struct InitializeConfig<'info> {
    #[account(mut)]
    pub admin: Signer<'info>,
    /// CHECK: simple clé enregistrée dans la configuration ; elle ne signe rien ici.
    pub publication_authority: UncheckedAccount<'info>,
    #[account(
        init,
        payer = admin,
        space = 8 + Config::INIT_SPACE,
        seeds = [CONFIG_SEED],
        bump
    )]
    pub config: Account<'info, Config>,
    pub skr_mint: Account<'info, Mint>,
    #[account(
        init,
        payer = admin,
        seeds = [VAULT_SEED],
        bump,
        token::mint = skr_mint,
        token::authority = config,
    )]
    pub vault: Account<'info, TokenAccount>,
    pub token_program: Program<'info, Token>,
    pub system_program: Program<'info, System>,
}

pub fn handle_initialize_config(ctx: Context<InitializeConfig>, params: ConfigParams) -> Result<()> {
    params.validate()?;
    let config = &mut ctx.accounts.config;
    config.admin = ctx.accounts.admin.key();
    config.publication_authority = ctx.accounts.publication_authority.key();
    config.skr_mint = ctx.accounts.skr_mint.key();
    config.vault = ctx.accounts.vault.key();
    config.pool_balance = 0;
    config.min_stake = params.min_stake;
    config.reward_cap = params.reward_cap;
    config.faucet_amount = params.faucet_amount;
    config.withdrawal_delay_seconds = params.withdrawal_delay_seconds;
    config.reward_rate_bps = params.reward_rate_bps;
    config.decay_bps = params.decay_bps;
    config.max_decay_days = params.max_decay_days;
    config.faucet_enabled = params.faucet_enabled;
    config.bump = ctx.bumps.config;
    config.vault_bump = ctx.bumps.vault;
    Ok(())
}
```

`program/programs/clockin/src/lib.rs` :

```rust
pub mod constants;
pub mod economy;
pub mod error;
pub mod instructions;
pub mod state;

use anchor_lang::prelude::*;

pub use constants::*;
pub use economy::*;
pub use instructions::*;
pub use state::*;

declare_id!("7TgCk9XekpU88Tiewd5VKfhmVJQyxNRR8915pzqU3rG1");

#[program]
pub mod clockin {
    use super::*;

    pub fn initialize_config(ctx: Context<InitializeConfig>, params: ConfigParams) -> Result<()> {
        instructions::initialize_config::handle_initialize_config(ctx, params)
    }
}
```

Supprimer les fichiers du scaffold :

```bash
cd /Users/clementlevoux/Dev/solana/clock_in
rm program/programs/clockin/src/instructions.rs \
   program/programs/clockin/src/instructions/initialize.rs \
   program/programs/clockin/src/instructions/increment.rs \
   program/programs/clockin/tests/test_initialize.rs
```

- [ ] **Step 6 : Écrire le harness**

`program/programs/clockin/tests/common/mod.rs` :

```rust
#![allow(dead_code)]

use {
    anchor_lang::{AccountDeserialize, InstructionData, ToAccountMetas},
    clockin::state::{CheckIn, Config, Profile},
    litesvm::{types::TransactionResult, LiteSVM},
    litesvm_token::{spl_token, CreateAssociatedTokenAccount, CreateMint},
    solana_clock::Clock,
    solana_instruction::Instruction,
    solana_keypair::Keypair,
    solana_message::{Message, VersionedMessage},
    solana_pubkey::Pubkey,
    solana_signer::Signer,
    solana_transaction::versioned::VersionedTransaction,
};

/// Unité de travail : mint de test à 9 décimales.
pub const SKR: u64 = 1_000_000_000;
pub const MIN_STAKE: u64 = 10 * SKR;
pub const FAUCET_AMOUNT: u64 = 100 * SKR;
pub const DAY: i64 = 86_400;

/// litesvm 0.10 embarque le runtime agave 3.1, qui ne charge pas les ELF SBPF v3
/// produits par défaut par `anchor build`. Sans cette garde, l'échec est un
/// `InvalidAccountData` opaque au chargement du programme.
fn assert_sbpf_v0(program_bytes: &[u8]) {
    let flags = u32::from_le_bytes(program_bytes[48..52].try_into().unwrap());
    assert_eq!(
        flags, 0,
        "clockin.so est compilé en SBPF v{flags} : relance `anchor build --arch v0`"
    );
}

pub struct User {
    pub keypair: Keypair,
    pub profile: Pubkey,
    pub token_account: Pubkey,
}

impl User {
    pub fn pubkey(&self) -> Pubkey {
        self.keypair.pubkey()
    }
}

pub struct Ctx {
    pub svm: LiteSVM,
    pub admin: Keypair,
    /// Autorité de publication : co-signe `check_in` (D4).
    pub authority: Keypair,
    pub mint: Pubkey,
    pub config: Pubkey,
    pub vault: Pubkey,
}

impl Ctx {
    /// Déploie le programme et crée le mint, sans initialiser la configuration.
    pub fn empty() -> Ctx {
        let mut svm = LiteSVM::new();
        let program_bytes = include_bytes!(concat!(
            env!("CARGO_TARGET_TMPDIR"),
            "/../deploy/clockin.so"
        ));
        assert_sbpf_v0(program_bytes);
        svm.add_program(clockin::id(), program_bytes).unwrap();

        let admin = Keypair::new();
        let authority = Keypair::new();
        svm.airdrop(&admin.pubkey(), 100 * 1_000_000_000).unwrap();
        svm.airdrop(&authority.pubkey(), 1_000_000_000).unwrap();

        let (config, _) = Pubkey::find_program_address(&[clockin::constants::CONFIG_SEED], &clockin::id());
        let (vault, _) = Pubkey::find_program_address(&[clockin::constants::VAULT_SEED], &clockin::id());

        // L'autorité de mint est le PDA Config : seul le programme peut créer du SKR de test.
        let mint = CreateMint::new(&mut svm, &admin)
            .authority(&config)
            .decimals(9)
            .send()
            .unwrap();

        Ctx { svm, admin, authority, mint, config, vault }
    }

    /// Déploie et initialise avec les paramètres de travail de la feuille de route.
    pub fn new() -> Ctx {
        let mut ctx = Ctx::empty();
        ctx.initialize_config_with(|_| {}).unwrap();
        ctx
    }

    pub fn initialize_config_with(
        &mut self,
        adjust: impl FnOnce(&mut clockin::instructions::ConfigParams),
    ) -> TransactionResult {
        let mut params = clockin::instructions::ConfigParams {
            min_stake: MIN_STAKE,
            reward_cap: SKR,
            faucet_amount: FAUCET_AMOUNT,
            withdrawal_delay_seconds: 172_800,
            reward_rate_bps: 100,
            decay_bps: 2500,
            max_decay_days: 30,
            faucet_enabled: true,
        };
        adjust(&mut params);
        let instruction = Instruction {
            program_id: clockin::id(),
            accounts: clockin::accounts::InitializeConfig {
                admin: self.admin.pubkey(),
                publication_authority: self.authority.pubkey(),
                config: self.config,
                skr_mint: self.mint,
                vault: self.vault,
                token_program: spl_token::ID,
                system_program: solana_system_interface::program::ID,
            }
            .to_account_metas(None),
            data: clockin::instruction::InitializeConfig { params }.data(),
        };
        let admin = self.admin.insecure_clone();
        self.send(&[instruction], &[&admin])
    }

    /// Envoie une transaction signée par `signers`, le premier payant les frais.
    pub fn send(&mut self, instructions: &[Instruction], signers: &[&Keypair]) -> TransactionResult {
        let payer = signers[0].pubkey();
        let blockhash = self.svm.latest_blockhash();
        let message = Message::new_with_blockhash(instructions, Some(&payer), &blockhash);
        let tx = VersionedTransaction::try_new(VersionedMessage::Legacy(message), signers).unwrap();
        let result = self.svm.send_transaction(tx);
        // Deux transactions identiques dans le même blockhash seraient rejetées
        // comme doublons : on force un nouveau blockhash après chaque envoi.
        self.svm.expire_blockhash();
        result
    }

    pub fn now(&self) -> i64 {
        self.svm.get_sysvar::<Clock>().unix_timestamp
    }

    pub fn today(&self) -> i64 {
        self.now().div_euclid(DAY)
    }

    /// Avance l'horloge de `days` jours et le slot en conséquence.
    pub fn warp_days(&mut self, days: i64) {
        self.warp_seconds(days * DAY);
    }

    pub fn warp_seconds(&mut self, seconds: i64) {
        let mut clock = self.svm.get_sysvar::<Clock>();
        clock.unix_timestamp += seconds;
        clock.slot += (seconds.max(0) as u64) * 1000 / 400;
        self.svm.set_sysvar(&clock);
        self.svm.expire_blockhash();
    }

    pub fn config_state(&self) -> Config {
        let account = self.svm.get_account(&self.config).expect("config absente");
        Config::try_deserialize(&mut account.data.as_slice()).unwrap()
    }

    pub fn profile_state(&self, profile: &Pubkey) -> Profile {
        let account = self.svm.get_account(profile).expect("profil absent");
        Profile::try_deserialize(&mut account.data.as_slice()).unwrap()
    }

    pub fn check_in_state(&self, check_in: &Pubkey) -> CheckIn {
        let account = self.svm.get_account(check_in).expect("check-in absent");
        CheckIn::try_deserialize(&mut account.data.as_slice()).unwrap()
    }

    pub fn token_balance(&self, token_account: &Pubkey) -> u64 {
        litesvm_token::get_spl_account::<spl_token::state::Account>(&self.svm, token_account)
            .map(|account| account.amount)
            .unwrap_or(0)
    }

    pub fn vault_balance(&self) -> u64 {
        self.token_balance(&self.vault)
    }

    pub fn profile_address(&self, owner: &Pubkey) -> Pubkey {
        Pubkey::find_program_address(
            &[clockin::constants::PROFILE_SEED, owner.as_ref()],
            &clockin::id(),
        )
        .0
    }

    pub fn check_in_address(&self, owner: &Pubkey, day: i64) -> Pubkey {
        Pubkey::find_program_address(
            &[clockin::constants::CHECKIN_SEED, owner.as_ref(), &day.to_le_bytes()],
            &clockin::id(),
        )
        .0
    }

    /// Crée un wallet avec des lamports et un compte de tokens SKR vide.
    /// Le profil et l'approvisionnement arrivent aux tâches 4 et 5.
    pub fn new_wallet(&mut self) -> User {
        let keypair = Keypair::new();
        self.svm.airdrop(&keypair.pubkey(), 10 * 1_000_000_000).unwrap();
        let owner = keypair.pubkey();
        let profile = self.profile_address(&owner);
        // Copies locales : `self.svm` est emprunté en mutable par le builder,
        // donc on ne peut pas lire `self.mint` ni `self.admin` au même moment.
        let admin = self.admin.insecure_clone();
        let mint = self.mint;
        let token_account = CreateAssociatedTokenAccount::new(&mut self.svm, &admin, &mint)
            .owner(&owner)
            .send()
            .unwrap();
        User { profile, keypair, token_account }
    }
}
```

Ajouter les dépendances manquantes révélées par ce harness dans `Cargo.toml` :

```toml
solana-pubkey = "3.0"
solana-system-interface = "2.0"
```

- [ ] **Step 7 : Construire puis lancer les tests**

Run : `cd program && anchor build --arch v0 && cargo test --test test_config`
Expected : PASS, 2 tests. Si `anchor build` échoue sur une dépendance manquante, l'ajouter et relancer.

- [ ] **Step 8 : Commit**

```bash
git add program/
git commit -m "feat(program): comptes Config/Profile/CheckIn, initialize_config et harness litesvm"
```

---

### Task 3 : `update_config`, rotation d'autorité et amorçage du pool

Le pool démarre vide : tant que personne n'a manqué un jour, personne ne gagne (§6.2).
L'admin l'amorce par un dépôt explicite. `update_config` permet de calibrer les
paramètres sur devnet sans redéployer (§15.2).

**Files:**
- Modify: `program/programs/clockin/src/instructions/initialize_config.rs`, `src/lib.rs`
- Modify: `program/programs/clockin/tests/common/mod.rs`, `tests/test_config.rs`

**Interfaces:**
- Consumes : `ConfigParams`, `Ctx` (tâche 2).
- Produces : `seed_pool(amount: u64)`, `update_config(params: ConfigParams)`, `set_publication_authority(new_authority: Pubkey)`, helpers `Ctx::seed_pool(amount)`, `Ctx::update_config(..)`, `Ctx::set_publication_authority(&Pubkey)`, `Ctx::mint_for_tests(&Pubkey, u64)`.

- [ ] **Step 1 : Écrire les tests**

Ajouter à `program/programs/clockin/tests/test_config.rs` :

```rust
#[test]
fn seed_pool_moves_tokens_into_the_vault_and_credits_the_pool() {
    let mut ctx = Ctx::new();
    ctx.seed_pool(500 * SKR).unwrap();

    assert_eq!(ctx.vault_balance(), 500 * SKR);
    assert_eq!(ctx.config_state().pool_balance, 500 * SKR);
}

#[test]
fn update_config_recalibrates_without_touching_the_pool() {
    let mut ctx = Ctx::new();
    ctx.seed_pool(500 * SKR).unwrap();

    ctx.update_config(|params| {
        params.decay_bps = 5000;
        params.reward_rate_bps = 200;
    })
    .unwrap();

    let config = ctx.config_state();
    assert_eq!(config.decay_bps, 5000);
    assert_eq!(config.reward_rate_bps, 200);
    assert_eq!(config.pool_balance, 500 * SKR, "le pool ne doit pas bouger");
}

#[test]
fn the_admin_can_rotate_the_publication_authority() {
    let mut ctx = Ctx::new();
    let replacement = Keypair::new();

    ctx.set_publication_authority(&replacement.pubkey()).unwrap();

    assert_eq!(ctx.config_state().publication_authority, replacement.pubkey());
}

#[test]
fn update_config_refuses_a_non_admin_signer() {
    let mut ctx = Ctx::new();
    let intruder = ctx.new_wallet();
    let result = ctx.update_config_as(&intruder.keypair.insecure_clone(), |params| {
        params.min_stake = 0;
    });
    assert!(result.is_err(), "seul l'admin peut recalibrer");
}
```

- [ ] **Step 2 : Vérifier l'échec**

Run : `cd program && cargo test --test test_config`
Expected : FAIL — `no method named 'seed_pool' found for struct 'Ctx'`.

- [ ] **Step 3 : Implémenter les deux instructions**

Ajouter à `program/programs/clockin/src/instructions/initialize_config.rs` :

```rust
use anchor_spl::token::{transfer_checked, TransferChecked};

#[derive(Accounts)]
pub struct UpdateConfig<'info> {
    #[account(address = config.admin @ ClockInError::InvalidConfigParam)]
    pub admin: Signer<'info>,
    #[account(mut, seeds = [CONFIG_SEED], bump = config.bump)]
    pub config: Account<'info, Config>,
}

pub fn handle_update_config(ctx: Context<UpdateConfig>, params: ConfigParams) -> Result<()> {
    params.validate()?;
    let config = &mut ctx.accounts.config;
    config.min_stake = params.min_stake;
    config.reward_cap = params.reward_cap;
    config.faucet_amount = params.faucet_amount;
    config.withdrawal_delay_seconds = params.withdrawal_delay_seconds;
    config.reward_rate_bps = params.reward_rate_bps;
    config.decay_bps = params.decay_bps;
    config.max_decay_days = params.max_decay_days;
    config.faucet_enabled = params.faucet_enabled;
    Ok(())
}

#[derive(Accounts)]
pub struct SeedPool<'info> {
    #[account(mut, address = config.admin @ ClockInError::InvalidConfigParam)]
    pub admin: Signer<'info>,
    #[account(mut, seeds = [CONFIG_SEED], bump = config.bump)]
    pub config: Account<'info, Config>,
    #[account(address = config.skr_mint)]
    pub skr_mint: Account<'info, Mint>,
    #[account(
        mut,
        constraint = admin_token_account.mint == config.skr_mint @ ClockInError::InvalidOwnerTokenAccount,
        constraint = admin_token_account.owner == admin.key() @ ClockInError::InvalidOwnerTokenAccount,
    )]
    pub admin_token_account: Account<'info, TokenAccount>,
    #[account(mut, address = config.vault)]
    pub vault: Account<'info, TokenAccount>,
    pub token_program: Program<'info, Token>,
}

/// Amorçage du pool (§6.2) : dépôt explicite de l'admin, présenté comme tel
/// dans le pitch. Aucun token n'est créé ici, seulement déplacé.
pub fn handle_seed_pool(ctx: Context<SeedPool>, amount: u64) -> Result<()> {
    require!(amount > 0, ClockInError::InvalidAmount);
    let decimals = ctx.accounts.skr_mint.decimals;
    transfer_checked(
        CpiContext::new(
            ctx.accounts.token_program.to_account_info(),
            TransferChecked {
                from: ctx.accounts.admin_token_account.to_account_info(),
                mint: ctx.accounts.skr_mint.to_account_info(),
                to: ctx.accounts.vault.to_account_info(),
                authority: ctx.accounts.admin.to_account_info(),
            },
        ),
        amount,
        decimals,
    )?;
    let config = &mut ctx.accounts.config;
    config.pool_balance = config
        .pool_balance
        .checked_add(amount)
        .ok_or(ClockInError::InvalidAmount)?;
    Ok(())
}
```

Toujours dans le même fichier, la rotation de l'autorité de publication. Elle
existe dès maintenant parce que l'autorité change au moins une fois, en semaine 2 :
de la clé de développement du plan 02 à celle du keyserver du plan 03.

```rust
pub fn handle_set_publication_authority(
    ctx: Context<UpdateConfig>,
    new_authority: Pubkey,
) -> Result<()> {
    ctx.accounts.config.publication_authority = new_authority;
    Ok(())
}
```

Déclarer dans `src/lib.rs`, dans `#[program]` :

```rust
    pub fn update_config(ctx: Context<UpdateConfig>, params: ConfigParams) -> Result<()> {
        instructions::initialize_config::handle_update_config(ctx, params)
    }

    pub fn set_publication_authority(
        ctx: Context<UpdateConfig>,
        new_authority: Pubkey,
    ) -> Result<()> {
        instructions::initialize_config::handle_set_publication_authority(ctx, new_authority)
    }

    pub fn seed_pool(ctx: Context<SeedPool>, amount: u64) -> Result<()> {
        instructions::initialize_config::handle_seed_pool(ctx, amount)
    }
```

- [ ] **Step 4 : Étendre le harness**

Ajouter dans `impl Ctx` (`tests/common/mod.rs`) :

```rust
    /// Crée du SKR de test. L'autorité de mint étant le PDA Config, le harness
    /// passe par un compte admin approvisionné une fois pour toutes, via le
    /// programme : voir `seed_pool` qui consomme ce solde.
    pub fn admin_token_account(&mut self) -> Pubkey {
        if let Some(existing) = self.admin_token {
            return existing;
        }
        let admin = self.admin.insecure_clone();
        let mint = self.mint;
        let owner = admin.pubkey();
        let account = CreateAssociatedTokenAccount::new(&mut self.svm, &admin, &mint)
            .owner(&owner)
            .send()
            .unwrap();
        self.admin_token = Some(account);
        account
    }

    /// Amorce le pool : mint vers l'admin par le faucet administratif du test,
    /// puis dépôt dans le vault.
    pub fn seed_pool(&mut self, amount: u64) -> TransactionResult {
        let admin_token = self.admin_token_account();
        self.mint_for_tests(&admin_token, amount);
        let instruction = Instruction {
            program_id: clockin::id(),
            accounts: clockin::accounts::SeedPool {
                admin: self.admin.pubkey(),
                config: self.config,
                skr_mint: self.mint,
                admin_token_account: admin_token,
                vault: self.vault,
                token_program: spl_token::ID,
            }
            .to_account_metas(None),
            data: clockin::instruction::SeedPool { amount }.data(),
        };
        let admin = self.admin.insecure_clone();
        self.send(&[instruction], &[&admin])
    }

    /// Écrit directement un solde de tokens dans un compte existant.
    /// L'autorité de mint appartenant au programme, c'est le seul moyen pour un
    /// test de fabriquer un solde arbitraire sans passer par le faucet métier.
    pub fn mint_for_tests(&mut self, token_account: &Pubkey, amount: u64) {
        use solana_program_pack::Pack;
        let mut account = self.svm.get_account(token_account).expect("compte de tokens absent");
        let mut state = spl_token::state::Account::unpack(&account.data[..spl_token::state::Account::LEN]).unwrap();
        state.amount += amount;
        spl_token::state::Account::pack(state, &mut account.data[..spl_token::state::Account::LEN]).unwrap();
        self.svm.set_account(*token_account, account).unwrap();
    }

    pub fn update_config(
        &mut self,
        adjust: impl FnOnce(&mut clockin::instructions::ConfigParams),
    ) -> TransactionResult {
        let admin = self.admin.insecure_clone();
        self.update_config_as(&admin, adjust)
    }

    pub fn set_publication_authority(&mut self, new_authority: &Pubkey) -> TransactionResult {
        let instruction = Instruction {
            program_id: clockin::id(),
            accounts: clockin::accounts::UpdateConfig {
                admin: self.admin.pubkey(),
                config: self.config,
            }
            .to_account_metas(None),
            data: clockin::instruction::SetPublicationAuthority { new_authority: *new_authority }.data(),
        };
        let admin = self.admin.insecure_clone();
        self.send(&[instruction], &[&admin])
    }

    pub fn update_config_as(
        &mut self,
        signer: &Keypair,
        adjust: impl FnOnce(&mut clockin::instructions::ConfigParams),
    ) -> TransactionResult {
        let current = self.config_state();
        let mut params = clockin::instructions::ConfigParams {
            min_stake: current.min_stake,
            reward_cap: current.reward_cap,
            faucet_amount: current.faucet_amount,
            withdrawal_delay_seconds: current.withdrawal_delay_seconds,
            reward_rate_bps: current.reward_rate_bps,
            decay_bps: current.decay_bps,
            max_decay_days: current.max_decay_days,
            faucet_enabled: current.faucet_enabled,
        };
        adjust(&mut params);
        let instruction = Instruction {
            program_id: clockin::id(),
            accounts: clockin::accounts::UpdateConfig {
                admin: signer.pubkey(),
                config: self.config,
            }
            .to_account_metas(None),
            data: clockin::instruction::UpdateConfig { params }.data(),
        };
        let signer = signer.insecure_clone();
        self.send(&[instruction], &[&signer])
    }
```

Ajouter le champ `admin_token: Option<Pubkey>` à `struct Ctx`, initialisé à `None` dans `Ctx::empty()`, et `solana-program-pack = "3.0"` aux `dev-dependencies`.

- [ ] **Step 5 : Lancer les tests**

Run : `cd program && anchor build --arch v0 && cargo test --test test_config`
Expected : PASS, 6 tests.

- [ ] **Step 6 : Commit**

```bash
git add program/
git commit -m "feat(program): update_config, rotation d'autorite et amorcage du pool"
```

---

### Task 4 : `create_profile` et `faucet`

**Files:**
- Create: `program/programs/clockin/src/instructions/profile.rs`
- Modify: `src/instructions/mod.rs`, `src/lib.rs`, `tests/common/mod.rs`
- Test: `program/programs/clockin/tests/test_profile.rs`

**Interfaces:**
- Consumes : `Config`, `Profile`, `Ctx::new_wallet()`.
- Produces : `create_profile()`, `faucet()`, helpers `Ctx::create_profile(&User)`, `Ctx::faucet(&User)`, `Ctx::new_user()` (wallet + profil + faucet).

- [ ] **Step 1 : Écrire les tests**

`program/programs/clockin/tests/test_profile.rs` :

```rust
mod common;

use common::{Ctx, FAUCET_AMOUNT};

#[test]
fn create_profile_starts_inactive_and_empty() {
    let mut ctx = Ctx::new();
    let user = ctx.new_wallet();
    ctx.create_profile(&user).unwrap();

    let profile = ctx.profile_state(&user.profile);
    assert_eq!(profile.owner, user.pubkey());
    assert_eq!(profile.staked, 0);
    assert!(!profile.active);
    assert_eq!(profile.streak, 0);
    assert_eq!(profile.total_checkins, 0);
    assert!(!profile.faucet_claimed);
}

#[test]
fn faucet_pays_once_and_only_once() {
    let mut ctx = Ctx::new();
    let user = ctx.new_wallet();
    ctx.create_profile(&user).unwrap();

    ctx.faucet(&user).unwrap();
    assert_eq!(ctx.token_balance(&user.token_account), FAUCET_AMOUNT);
    assert!(ctx.profile_state(&user.profile).faucet_claimed);

    assert!(ctx.faucet(&user).is_err(), "un second faucet doit échouer");
    assert_eq!(ctx.token_balance(&user.token_account), FAUCET_AMOUNT);
}

#[test]
fn faucet_refuses_when_disabled_by_configuration() {
    let mut ctx = Ctx::new();
    ctx.update_config(|params| params.faucet_enabled = false).unwrap();
    let user = ctx.new_wallet();
    ctx.create_profile(&user).unwrap();

    assert!(ctx.faucet(&user).is_err(), "faucet coupé en configuration");
}
```

- [ ] **Step 2 : Vérifier l'échec**

Run : `cd program && cargo test --test test_profile`
Expected : FAIL — `no method named 'create_profile'`.

- [ ] **Step 3 : Implémenter**

`program/programs/clockin/src/instructions/profile.rs` :

```rust
use anchor_lang::prelude::*;
use anchor_spl::token::{mint_to, Mint, MintTo, Token, TokenAccount};

use crate::{constants::*, error::ClockInError, state::{Config, Profile}};

#[derive(Accounts)]
pub struct CreateProfile<'info> {
    #[account(mut)]
    pub owner: Signer<'info>,
    #[account(
        init,
        payer = owner,
        space = 8 + Profile::INIT_SPACE,
        seeds = [PROFILE_SEED, owner.key().as_ref()],
        bump
    )]
    pub profile: Account<'info, Profile>,
    pub system_program: Program<'info, System>,
}

pub fn handle_create_profile(ctx: Context<CreateProfile>) -> Result<()> {
    let profile = &mut ctx.accounts.profile;
    profile.owner = ctx.accounts.owner.key();
    profile.staked = 0;
    profile.settled_day = 0;
    profile.last_checkin_day = 0;
    profile.exit_requested_at = 0;
    profile.exit_unlock_at = 0;
    profile.total_checkins = 0;
    profile.streak = 0;
    profile.active = false;
    profile.faucet_claimed = false;
    profile.bump = ctx.bumps.profile;
    Ok(())
}

#[derive(Accounts)]
pub struct Faucet<'info> {
    #[account(mut)]
    pub owner: Signer<'info>,
    #[account(seeds = [CONFIG_SEED], bump = config.bump)]
    pub config: Account<'info, Config>,
    #[account(
        mut,
        seeds = [PROFILE_SEED, owner.key().as_ref()],
        bump = profile.bump,
        has_one = owner
    )]
    pub profile: Account<'info, Profile>,
    #[account(mut, address = config.skr_mint)]
    pub skr_mint: Account<'info, Mint>,
    #[account(
        mut,
        constraint = owner_token_account.mint == config.skr_mint @ ClockInError::InvalidOwnerTokenAccount,
        constraint = owner_token_account.owner == owner.key() @ ClockInError::InvalidOwnerTokenAccount,
    )]
    pub owner_token_account: Account<'info, TokenAccount>,
    pub token_program: Program<'info, Token>,
}

/// Faucet devnet (§10). Coupé en mainnet par `config.faucet_enabled`, pas par
/// une feature de compilation : le même binaire sert les deux réseaux.
pub fn handle_faucet(ctx: Context<Faucet>) -> Result<()> {
    require!(ctx.accounts.config.faucet_enabled, ClockInError::FaucetDisabled);
    require!(!ctx.accounts.profile.faucet_claimed, ClockInError::FaucetAlreadyClaimed);

    let amount = ctx.accounts.config.faucet_amount;
    let bump = ctx.accounts.config.bump;
    let seeds: &[&[u8]] = &[CONFIG_SEED, &[bump]];
    mint_to(
        CpiContext::new_with_signer(
            ctx.accounts.token_program.to_account_info(),
            MintTo {
                mint: ctx.accounts.skr_mint.to_account_info(),
                to: ctx.accounts.owner_token_account.to_account_info(),
                authority: ctx.accounts.config.to_account_info(),
            },
            &[seeds],
        ),
        amount,
    )?;
    ctx.accounts.profile.faucet_claimed = true;
    Ok(())
}
```

Ajouter `pub mod profile; pub use profile::*;` à `src/instructions/mod.rs`, et dans `#[program]` :

```rust
    pub fn create_profile(ctx: Context<CreateProfile>) -> Result<()> {
        instructions::profile::handle_create_profile(ctx)
    }

    pub fn faucet(ctx: Context<Faucet>) -> Result<()> {
        instructions::profile::handle_faucet(ctx)
    }
```

- [ ] **Step 4 : Étendre le harness**

Ajouter dans `impl Ctx` :

```rust
    pub fn create_profile(&mut self, user: &User) -> TransactionResult {
        let instruction = Instruction {
            program_id: clockin::id(),
            accounts: clockin::accounts::CreateProfile {
                owner: user.pubkey(),
                profile: user.profile,
                system_program: solana_system_interface::program::ID,
            }
            .to_account_metas(None),
            data: clockin::instruction::CreateProfile {}.data(),
        };
        let signer = user.keypair.insecure_clone();
        self.send(&[instruction], &[&signer])
    }

    pub fn faucet(&mut self, user: &User) -> TransactionResult {
        let instruction = Instruction {
            program_id: clockin::id(),
            accounts: clockin::accounts::Faucet {
                owner: user.pubkey(),
                config: self.config,
                profile: user.profile,
                skr_mint: self.mint,
                owner_token_account: user.token_account,
                token_program: spl_token::ID,
            }
            .to_account_metas(None),
            data: clockin::instruction::Faucet {}.data(),
        };
        let signer = user.keypair.insecure_clone();
        self.send(&[instruction], &[&signer])
    }

    /// Wallet + profil + faucet : l'utilisateur type des tests suivants.
    pub fn new_user(&mut self) -> User {
        let user = self.new_wallet();
        self.create_profile(&user).unwrap();
        self.faucet(&user).unwrap();
        user
    }
```

- [ ] **Step 5 : Lancer les tests**

Run : `cd program && anchor build --arch v0 && cargo test --test test_profile`
Expected : PASS, 3 tests.

- [ ] **Step 6 : Commit**

```bash
git add program/
git commit -m "feat(program): create_profile et faucet devnet"
```

---

### Task 5 : Règlement des jours manqués et `stake`

Le règlement est la routine partagée par `check_in`, `stake`, `request_exit`, `reap`
et `finalize_exit` (§6). Elle est écrite ici parce que `stake` est la première
instruction qui en dépend : sur une position active, les jours manqués sont réglés
**avant** l'ajout du dépôt, pour qu'aucun token frais ne subisse un decay rétroactif.

**Files:**
- Modify: `program/programs/clockin/src/economy.rs` (ajout de `settle_bound`), `src/state.rs` (ajout de `Profile::settle_through`)
- Create: `program/programs/clockin/src/instructions/stake.rs`
- Modify: `src/instructions/mod.rs`, `src/lib.rs`, `tests/common/mod.rs`
- Test: `program/programs/clockin/tests/test_stake.rs`

**Interfaces:**
- Consumes : `decay`, `day_of` (tâche 1) ; `Ctx::new_user()` (tâche 4).
- Produces : `settle_bound(exit_unlock_at: i64, today: i64) -> i64`, `Profile::settle_bound(&self, today: i64) -> i64`, `Profile::settle_through(&mut self, config: &mut Config, through_day: i64)`, instruction `stake(amount: u64)`, helper `Ctx::stake(&User, u64)`.

- [ ] **Step 1 : Écrire le test pur de la borne de règlement**

Ajouter dans le `mod tests` de `program/programs/clockin/src/economy.rs` :

```rust
    #[test]
    fn settle_bound_stops_at_yesterday_without_a_pending_exit() {
        assert_eq!(settle_bound(0, 100), 99);
    }

    #[test]
    fn settle_bound_never_passes_the_last_full_day_before_unlock() {
        // Déblocage au milieu du jour 101 : le dernier jour entièrement terminé
        // avant le déblocage est le jour 100 (§6.3).
        let unlock_at = 101 * DAY_SECONDS + 3_600;
        assert_eq!(settle_bound(unlock_at, 105), 100);
        assert_eq!(settle_bound(unlock_at, 100), 99, "on ne pénalise jamais au-delà d'hier");
    }
```

- [ ] **Step 2 : Écrire les tests d'intégration de `stake`**

`program/programs/clockin/tests/test_stake.rs` :

```rust
mod common;

use common::{Ctx, SKR};

#[test]
fn first_stake_opens_the_position_with_yesterday_settled() {
    let mut ctx = Ctx::new();
    let user = ctx.new_user();

    ctx.stake(&user, 50 * SKR).unwrap();

    let profile = ctx.profile_state(&user.profile);
    assert!(profile.active);
    assert_eq!(profile.staked, 50 * SKR);
    assert_eq!(profile.settled_day, ctx.today() - 1);
    assert_eq!(profile.streak, 0);
    assert_eq!(ctx.vault_balance(), 50 * SKR);
    assert_eq!(ctx.token_balance(&user.token_account), 50 * SKR);
}

#[test]
fn a_second_stake_the_same_day_adds_without_decay() {
    let mut ctx = Ctx::new();
    let user = ctx.new_user();

    ctx.stake(&user, 30 * SKR).unwrap();
    ctx.stake(&user, 20 * SKR).unwrap();

    assert_eq!(ctx.profile_state(&user.profile).staked, 50 * SKR);
    assert_eq!(ctx.vault_balance(), 50 * SKR);
}

#[test]
fn staking_after_two_missed_days_decays_the_old_balance_only() {
    let mut ctx = Ctx::new();
    let user = ctx.new_user();
    ctx.stake(&user, 40 * SKR).unwrap();

    // Jour J : dépôt. Jours J+1 et J+2 manqués. Dépôt le jour J+3.
    ctx.warp_days(3);
    ctx.stake(&user, 10 * SKR).unwrap();

    // 40 SKR décimés deux fois à 25 % = 22,5 SKR, plus 10 SKR intacts.
    let profile = ctx.profile_state(&user.profile);
    assert_eq!(profile.staked, 32_500_000_000);
    assert_eq!(ctx.config_state().pool_balance, 17_500_000_000);
    assert_eq!(profile.settled_day, ctx.today() - 1);
    assert_eq!(ctx.vault_balance(), 50 * SKR, "les tokens ne bougent jamais lors d'un decay");
}

#[test]
fn staking_zero_is_refused() {
    let mut ctx = Ctx::new();
    let user = ctx.new_user();
    assert!(ctx.stake(&user, 0).is_err());
}
```

- [ ] **Step 3 : Vérifier l'échec**

Run : `cd program && cargo test -p clockin --lib && cargo test --test test_stake`
Expected : FAIL — `cannot find function 'settle_bound'`, puis `no method named 'stake'`.

- [ ] **Step 4 : Implémenter le règlement**

Ajouter à `program/programs/clockin/src/economy.rs` :

```rust
/// Dernier jour pénalisable. Hier en temps normal ; pendant une sortie, jamais
/// au-delà du dernier jour entièrement terminé avant le déblocage (§6.3, §6.4).
pub fn settle_bound(exit_unlock_at: i64, today: i64) -> i64 {
    let by_today = today - 1;
    if exit_unlock_at > 0 {
        by_today.min(day_of(exit_unlock_at) - 1)
    } else {
        by_today
    }
}
```

Ajouter à `program/programs/clockin/src/state.rs` (après les trois comptes) :

```rust
use crate::economy::{decay, settle_bound};

impl Profile {
    pub fn settle_bound(&self, today: i64) -> i64 {
        settle_bound(self.exit_unlock_at, today)
    }

    /// Applique le decay des jours non réglés jusqu'à `through_day` inclus.
    /// Le montant perdu alimente le pool ; aucun token SPL ne bouge.
    /// Séparer `settled_day` de `last_checkin_day` est ce qui rend `reap` sûr
    /// contre le double-decay : un jour réglé ne l'est jamais deux fois.
    pub fn settle_through(&mut self, config: &mut Config, through_day: i64) {
        if !self.active {
            return;
        }
        let missed = through_day - self.settled_day;
        if missed <= 0 {
            return;
        }
        let outcome = decay(self.staked, missed, config.decay_bps, config.max_decay_days);
        self.staked = outcome.remaining;
        config.pool_balance = config.pool_balance.saturating_add(outcome.lost);
        self.settled_day = through_day;
        self.streak = 0;
    }
}
```

- [ ] **Step 5 : Implémenter `stake`**

`program/programs/clockin/src/instructions/stake.rs` :

```rust
use anchor_lang::prelude::*;
use anchor_spl::token::{transfer_checked, Mint, Token, TokenAccount, TransferChecked};

use crate::{constants::*, economy::day_of, error::ClockInError, state::{Config, Profile}};

#[derive(Accounts)]
pub struct Stake<'info> {
    #[account(mut)]
    pub owner: Signer<'info>,
    #[account(mut, seeds = [CONFIG_SEED], bump = config.bump)]
    pub config: Account<'info, Config>,
    #[account(
        mut,
        seeds = [PROFILE_SEED, owner.key().as_ref()],
        bump = profile.bump,
        has_one = owner
    )]
    pub profile: Account<'info, Profile>,
    #[account(address = config.skr_mint)]
    pub skr_mint: Account<'info, Mint>,
    #[account(
        mut,
        constraint = owner_token_account.mint == config.skr_mint @ ClockInError::InvalidOwnerTokenAccount,
        constraint = owner_token_account.owner == owner.key() @ ClockInError::InvalidOwnerTokenAccount,
    )]
    pub owner_token_account: Account<'info, TokenAccount>,
    #[account(mut, address = config.vault)]
    pub vault: Account<'info, TokenAccount>,
    pub token_program: Program<'info, Token>,
}

pub fn handle_stake(ctx: Context<Stake>, amount: u64) -> Result<()> {
    require!(amount > 0, ClockInError::InvalidAmount);
    let today = day_of(Clock::get()?.unix_timestamp);

    {
        let profile = &mut ctx.accounts.profile;
        let config = &mut ctx.accounts.config;
        // Un dépôt pendant une sortie rendrait le montant de sortie illisible (§5).
        require!(profile.exit_unlock_at == 0, ClockInError::StakeDuringExit);
        if profile.active {
            // Régler AVANT d'ajouter : le dépôt neuf ne subit aucun decay rétroactif.
            let bound = profile.settle_bound(today);
            profile.settle_through(config, bound);
        } else {
            profile.active = true;
            profile.settled_day = today - 1;
            profile.streak = 0;
        }
    }

    let decimals = ctx.accounts.skr_mint.decimals;
    transfer_checked(
        CpiContext::new(
            ctx.accounts.token_program.to_account_info(),
            TransferChecked {
                from: ctx.accounts.owner_token_account.to_account_info(),
                mint: ctx.accounts.skr_mint.to_account_info(),
                to: ctx.accounts.vault.to_account_info(),
                authority: ctx.accounts.owner.to_account_info(),
            },
        ),
        amount,
        decimals,
    )?;

    let profile = &mut ctx.accounts.profile;
    profile.staked = profile
        .staked
        .checked_add(amount)
        .ok_or(ClockInError::InvalidAmount)?;
    Ok(())
}
```

Déclarer `pub mod stake; pub use stake::*;` dans `src/instructions/mod.rs` et dans `#[program]` :

```rust
    pub fn stake(ctx: Context<Stake>, amount: u64) -> Result<()> {
        instructions::stake::handle_stake(ctx, amount)
    }
```

- [ ] **Step 6 : Étendre le harness**

Ajouter dans `impl Ctx` :

```rust
    pub fn stake(&mut self, user: &User, amount: u64) -> TransactionResult {
        let instruction = Instruction {
            program_id: clockin::id(),
            accounts: clockin::accounts::Stake {
                owner: user.pubkey(),
                config: self.config,
                profile: user.profile,
                skr_mint: self.mint,
                owner_token_account: user.token_account,
                vault: self.vault,
                token_program: spl_token::ID,
            }
            .to_account_metas(None),
            data: clockin::instruction::Stake { amount }.data(),
        };
        let signer = user.keypair.insecure_clone();
        self.send(&[instruction], &[&signer])
    }
```

- [ ] **Step 7 : Lancer les tests**

Run : `cd program && anchor build --arch v0 && cargo test -p clockin --lib && cargo test --test test_stake`
Expected : PASS — 13 tests purs, 4 tests d'intégration.

- [ ] **Step 8 : Commit**

```bash
git add program/
git commit -m "feat(program): reglement des jours manques et instruction stake"
```

---

### Task 6 : `check_in`

Le cœur du produit. Cette instruction règle les jours manqués, vérifie le seuil,
verse la récompense bornée par le pool, avance le streak et inscrit le commitment.
Elle exige la **co-signature de l'autorité de publication** (D4) : un client modifié
qui publierait sans passer par le contrôle serveur ne peut pas toucher de récompense.

**Files:**
- Create: `program/programs/clockin/src/instructions/check_in.rs`
- Modify: `src/instructions/mod.rs`, `src/lib.rs`, `tests/common/mod.rs`
- Test: `program/programs/clockin/tests/test_check_in.rs`, `tests/test_decay.rs`

**Interfaces:**
- Consumes : `Profile::settle_through`, `reward`, `day_of`, `Ctx::stake`.
- Produces : `check_in(day: i64, commitment: [u8; 32], blob_ref: [u8; 32])`, helpers `Ctx::check_in(&User)`, `Ctx::check_in_signed_by(&User, &Keypair)`, `Ctx::check_in_on_day(&User, i64)`.

- [ ] **Step 1 : Écrire les tests de check-in**

`program/programs/clockin/tests/test_check_in.rs` :

```rust
mod common;

use common::{Ctx, SKR};
use solana_keypair::Keypair;
use solana_signer::Signer;

#[test]
fn a_check_in_pays_a_capped_reward_and_records_the_commitment() {
    let mut ctx = Ctx::new();
    ctx.seed_pool(100 * SKR).unwrap();
    let user = ctx.new_user();
    ctx.stake(&user, 50 * SKR).unwrap();

    ctx.check_in(&user).unwrap();

    let profile = ctx.profile_state(&user.profile);
    // 1 % de 50 SKR = 0,5 SKR, sous le plafond de 1 SKR et sous le pool.
    assert_eq!(profile.staked, 50 * SKR + SKR / 2);
    assert_eq!(profile.streak, 1);
    assert_eq!(profile.total_checkins, 1);
    assert_eq!(profile.last_checkin_day, ctx.today());
    assert_eq!(profile.settled_day, ctx.today());
    assert_eq!(ctx.config_state().pool_balance, 100 * SKR - SKR / 2);

    let check_in = ctx.check_in_state(&ctx.check_in_address(&user.pubkey(), ctx.today()));
    assert_eq!(check_in.owner, user.pubkey());
    assert_eq!(check_in.day, ctx.today());
    assert_eq!(check_in.commitment, [7u8; 32]);
    assert_eq!(check_in.blob_ref, [9u8; 32]);
    assert_eq!(check_in.streak_at_checkin, 1);
    assert!(check_in.slot > 0);
}

#[test]
fn an_empty_pool_pays_nothing_but_the_check_in_still_succeeds() {
    let mut ctx = Ctx::new();
    let user = ctx.new_user();
    ctx.stake(&user, 50 * SKR).unwrap();

    ctx.check_in(&user).unwrap();

    let profile = ctx.profile_state(&user.profile);
    assert_eq!(profile.staked, 50 * SKR, "aucune récompense");
    assert_eq!(profile.streak, 1, "la boucle sociale n'est jamais bloquée par l'économie");
}

#[test]
fn a_second_check_in_the_same_day_is_structurally_impossible() {
    let mut ctx = Ctx::new();
    let user = ctx.new_user();
    ctx.stake(&user, 50 * SKR).unwrap();

    ctx.check_in(&user).unwrap();
    assert!(ctx.check_in(&user).is_err(), "le PDA du jour existe déjà");
}

#[test]
fn check_in_without_the_publication_authority_is_refused() {
    let mut ctx = Ctx::new();
    let user = ctx.new_user();
    ctx.stake(&user, 50 * SKR).unwrap();

    let impostor = Keypair::new();
    ctx.svm.airdrop(&impostor.pubkey(), 1_000_000_000).unwrap();

    assert!(
        ctx.check_in_signed_by(&user, &impostor).is_err(),
        "une autorisation de publication forgée doit échouer"
    );
}

#[test]
fn check_in_below_the_minimum_stake_is_refused() {
    let mut ctx = Ctx::new();
    let user = ctx.new_user();
    ctx.stake(&user, 11 * SKR).unwrap();

    // Deux jours manqués : 11 SKR tombent à 6,1875 SKR, sous les 10 SKR requis.
    ctx.warp_days(3);
    assert!(ctx.check_in(&user).is_err(), "il faut recharger pour rejouer");
    assert!(ctx.profile_state(&user.profile).staked < 10 * SKR);
}

#[test]
fn a_day_that_is_not_today_is_refused() {
    let mut ctx = Ctx::new();
    let user = ctx.new_user();
    ctx.stake(&user, 50 * SKR).unwrap();

    let yesterday = ctx.today() - 1;
    assert!(
        ctx.check_in_on_day(&user, yesterday).is_err(),
        "le jour est décidé par l'horloge du réseau, pas par le client"
    );
}

#[test]
fn check_in_without_an_open_position_is_refused() {
    let mut ctx = Ctx::new();
    let user = ctx.new_user();
    assert!(ctx.check_in(&user).is_err());
}
```

- [ ] **Step 2 : Écrire les tests de decay par le chemin `check_in`**

`program/programs/clockin/tests/test_decay.rs` :

```rust
mod common;

use common::{Ctx, SKR};

#[test]
fn one_missed_day_costs_a_quarter_and_resets_the_streak() {
    let mut ctx = Ctx::new();
    let user = ctx.new_user();
    ctx.stake(&user, 40 * SKR).unwrap();
    ctx.check_in(&user).unwrap();

    ctx.warp_days(2); // le jour intermédiaire est manqué
    ctx.check_in(&user).unwrap();

    let profile = ctx.profile_state(&user.profile);
    assert_eq!(profile.staked, 30 * SKR, "40 SKR décimés de 25 %");
    assert_eq!(profile.streak, 1, "le streak repart de zéro puis vaut 1");
    assert_eq!(ctx.config_state().pool_balance, 10 * SKR);
}

#[test]
fn a_regular_member_keeps_a_growing_streak() {
    let mut ctx = Ctx::new();
    ctx.seed_pool(100 * SKR).unwrap();
    let user = ctx.new_user();
    ctx.stake(&user, 50 * SKR).unwrap();

    for _ in 0..5 {
        ctx.check_in(&user).unwrap();
        ctx.warp_days(1);
    }

    let profile = ctx.profile_state(&user.profile);
    assert_eq!(profile.streak, 5);
    assert_eq!(profile.total_checkins, 5);
    assert!(profile.staked > 50 * SKR, "le solde d'un régulier grossit");
}

#[test]
fn beyond_max_decay_days_the_balance_is_wiped_into_the_pool() {
    let mut ctx = Ctx::new();
    let user = ctx.new_user();
    ctx.stake(&user, 80 * SKR).unwrap();
    ctx.check_in(&user).unwrap();

    ctx.warp_days(40);
    // Le check-in échoue (solde nul sous le minimum) mais le règlement a eu lieu.
    assert!(ctx.check_in(&user).is_err());
    assert_eq!(ctx.profile_state(&user.profile).staked, 0);
}
```

Note : le dernier test vérifie l'état **après une transaction échouée**. Une transaction
qui échoue ne persiste rien : le règlement est donc invisible. Remplacer l'assertion par
un `reap` explicite une fois la tâche 7 écrite ; en attendant, écrire :

```rust
    assert!(ctx.check_in(&user).is_err());
    assert_eq!(
        ctx.profile_state(&user.profile).staked,
        80 * SKR,
        "une transaction échouée ne persiste aucun règlement : c'est reap qui purge (tâche 7)"
    );
```

- [ ] **Step 3 : Vérifier l'échec**

Run : `cd program && cargo test --test test_check_in`
Expected : FAIL — `no method named 'check_in'`.

- [ ] **Step 4 : Implémenter**

`program/programs/clockin/src/instructions/check_in.rs` :

```rust
use anchor_lang::prelude::*;

use crate::{
    constants::*,
    economy::{day_of, reward},
    error::ClockInError,
    state::{CheckIn, Config, Profile},
};

#[derive(Accounts)]
#[instruction(day: i64)]
pub struct CheckInAccounts<'info> {
    #[account(mut)]
    pub owner: Signer<'info>,
    /// Autorisation de publication du backend (D4, §8.6). Sa signature porte sur
    /// cette transaction exacte : mêmes données d'instruction, même blockhash,
    /// donc une expiration naturelle et aucun rejeu possible.
    #[account(address = config.publication_authority @ ClockInError::InvalidConfigParam)]
    pub publication_authority: Signer<'info>,
    #[account(mut, seeds = [CONFIG_SEED], bump = config.bump)]
    pub config: Account<'info, Config>,
    #[account(
        mut,
        seeds = [PROFILE_SEED, owner.key().as_ref()],
        bump = profile.bump,
        has_one = owner
    )]
    pub profile: Account<'info, Profile>,
    #[account(
        init,
        payer = owner,
        space = 8 + CheckIn::INIT_SPACE,
        seeds = [CHECKIN_SEED, owner.key().as_ref(), &day.to_le_bytes()],
        bump
    )]
    pub check_in: Account<'info, CheckIn>,
    pub system_program: Program<'info, System>,
}

pub fn handle_check_in(
    ctx: Context<CheckInAccounts>,
    day: i64,
    commitment: [u8; 32],
    blob_ref: [u8; 32],
) -> Result<()> {
    let clock = Clock::get()?;
    let today = day_of(clock.unix_timestamp);
    // Le jour n'est en paramètre que pour dériver le PDA ; il est vérifié (D6).
    require!(day == today, ClockInError::DayMismatch);

    let config = &mut ctx.accounts.config;
    let profile = &mut ctx.accounts.profile;
    require!(profile.active, ClockInError::PositionInactive);
    if profile.exit_unlock_at > 0 {
        require!(
            clock.unix_timestamp < profile.exit_unlock_at,
            ClockInError::ExitUnlocked
        );
    }

    let bound = profile.settle_bound(today);
    profile.settle_through(config, bound);
    require!(profile.staked >= config.min_stake, ClockInError::InsufficientStake);

    let paid = reward(
        profile.staked,
        config.reward_rate_bps,
        config.reward_cap,
        config.pool_balance,
    );
    config.pool_balance -= paid;
    profile.staked += paid;
    profile.streak += 1;
    profile.settled_day = today;
    profile.last_checkin_day = today;
    profile.total_checkins += 1;

    let check_in = &mut ctx.accounts.check_in;
    check_in.owner = profile.owner;
    check_in.day = today;
    check_in.commitment = commitment;
    check_in.blob_ref = blob_ref;
    check_in.slot = clock.slot;
    check_in.streak_at_checkin = profile.streak;
    Ok(())
}
```

Déclarer `pub mod check_in; pub use check_in::*;` et dans `#[program]` :

```rust
    pub fn check_in(
        ctx: Context<CheckInAccounts>,
        day: i64,
        commitment: [u8; 32],
        blob_ref: [u8; 32],
    ) -> Result<()> {
        instructions::check_in::handle_check_in(ctx, day, commitment, blob_ref)
    }
```

- [ ] **Step 5 : Étendre le harness**

Ajouter dans `impl Ctx` :

```rust
    pub fn check_in(&mut self, user: &User) -> TransactionResult {
        let authority = self.authority.insecure_clone();
        let day = self.today();
        self.check_in_full(user, &authority, day, [7u8; 32], [9u8; 32])
    }

    pub fn check_in_signed_by(&mut self, user: &User, authority: &Keypair) -> TransactionResult {
        let day = self.today();
        self.check_in_full(user, authority, day, [7u8; 32], [9u8; 32])
    }

    pub fn check_in_on_day(&mut self, user: &User, day: i64) -> TransactionResult {
        let authority = self.authority.insecure_clone();
        self.check_in_full(user, &authority, day, [7u8; 32], [9u8; 32])
    }

    pub fn check_in_full(
        &mut self,
        user: &User,
        authority: &Keypair,
        day: i64,
        commitment: [u8; 32],
        blob_ref: [u8; 32],
    ) -> TransactionResult {
        let instruction = Instruction {
            program_id: clockin::id(),
            accounts: clockin::accounts::CheckInAccounts {
                owner: user.pubkey(),
                publication_authority: authority.pubkey(),
                config: self.config,
                profile: user.profile,
                check_in: self.check_in_address(&user.pubkey(), day),
                system_program: solana_system_interface::program::ID,
            }
            .to_account_metas(None),
            data: clockin::instruction::CheckIn { day, commitment, blob_ref }.data(),
        };
        let owner = user.keypair.insecure_clone();
        let authority = authority.insecure_clone();
        self.send(&[instruction], &[&owner, &authority])
    }
```

- [ ] **Step 6 : Lancer les tests**

Run : `cd program && anchor build --arch v0 && cargo test --test test_check_in --test test_decay`
Expected : PASS — 7 + 3 tests.

- [ ] **Step 7 : Commit**

```bash
git add program/
git commit -m "feat(program): check_in avec autorisation de publication co-signee"
```

---

### Task 7 : `reap`

Sans `reap`, un utilisateur qui ne revient jamais n'est jamais décimé et le pool
s'assèche (§6.4). L'instruction est permissionless : n'importe qui peut régler un
profil en retard. Le point délicat est l'absence de double-decay, garantie par
`settled_day`.

**Files:**
- Create: `program/programs/clockin/src/instructions/reap.rs`
- Modify: `src/instructions/mod.rs`, `src/lib.rs`, `tests/common/mod.rs`, `tests/test_decay.rs`
- Test: `program/programs/clockin/tests/test_reap.rs`

**Interfaces:**
- Consumes : `Profile::settle_bound`, `Profile::settle_through`.
- Produces : `reap()`, helper `Ctx::reap(&Pubkey)` (appelé par un tiers).

- [ ] **Step 1 : Écrire les tests**

`program/programs/clockin/tests/test_reap.rs` :

```rust
mod common;

use common::{Ctx, SKR};

#[test]
fn anyone_can_settle_a_late_profile_and_feed_the_pool() {
    let mut ctx = Ctx::new();
    let user = ctx.new_user();
    ctx.stake(&user, 40 * SKR).unwrap();
    ctx.check_in(&user).unwrap();

    ctx.warp_days(3); // deux jours entiers manqués
    let owner = user.pubkey();
    ctx.reap(&owner).unwrap();

    let profile = ctx.profile_state(&user.profile);
    assert_eq!(profile.staked, 22_500_000_000, "40 SKR décimés deux fois");
    assert_eq!(profile.streak, 0);
    assert_eq!(profile.settled_day, ctx.today() - 1);
    assert_eq!(ctx.config_state().pool_balance, 17_500_000_000);
}

#[test]
fn reap_then_check_in_the_same_day_does_not_decay_twice() {
    let mut ctx = Ctx::new();
    let user = ctx.new_user();
    ctx.stake(&user, 40 * SKR).unwrap();
    ctx.check_in(&user).unwrap();

    ctx.warp_days(2);
    let owner = user.pubkey();
    ctx.reap(&owner).unwrap();
    let after_reap = ctx.profile_state(&user.profile).staked;

    ctx.check_in(&user).unwrap();

    let profile = ctx.profile_state(&user.profile);
    assert_eq!(profile.staked, after_reap, "aucun decay supplémentaire, pool vide donc aucune récompense");
    assert_eq!(profile.streak, 1);
}

#[test]
fn reap_on_an_up_to_date_profile_is_refused() {
    let mut ctx = Ctx::new();
    let user = ctx.new_user();
    ctx.stake(&user, 40 * SKR).unwrap();
    ctx.check_in(&user).unwrap();

    let owner = user.pubkey();
    assert!(ctx.reap(&owner).is_err(), "rien à régler");
}

#[test]
fn reap_on_a_closed_position_is_refused() {
    let mut ctx = Ctx::new();
    let user = ctx.new_user();
    let owner = user.pubkey();
    assert!(ctx.reap(&owner).is_err(), "position jamais ouverte");
}
```

Corriger aussi le dernier test de `tests/test_decay.rs` — il devient vérifiable
maintenant que `reap` persiste le règlement :

```rust
#[test]
fn beyond_max_decay_days_the_balance_is_wiped_into_the_pool() {
    let mut ctx = Ctx::new();
    let user = ctx.new_user();
    ctx.stake(&user, 80 * SKR).unwrap();
    ctx.check_in(&user).unwrap();

    ctx.warp_days(40);
    let owner = user.pubkey();
    ctx.reap(&owner).unwrap();

    assert_eq!(ctx.profile_state(&user.profile).staked, 0);
    assert_eq!(ctx.config_state().pool_balance, 80 * SKR);
    assert!(ctx.check_in(&user).is_err(), "il faut recharger pour rejouer");
}
```

- [ ] **Step 2 : Vérifier l'échec**

Run : `cd program && cargo test --test test_reap`
Expected : FAIL — `no method named 'reap'`.

- [ ] **Step 3 : Implémenter**

`program/programs/clockin/src/instructions/reap.rs` :

```rust
use anchor_lang::prelude::*;

use crate::{constants::*, economy::day_of, error::ClockInError, state::{Config, Profile}};

#[derive(Accounts)]
pub struct Reap<'info> {
    /// Instruction permissionless : n'importe qui peut régler un profil en retard.
    /// Aucun bonus n'est versé à l'appelant en v1 (§6.4).
    pub caller: Signer<'info>,
    /// CHECK: propriétaire de la position, lié au profil par `has_one`.
    pub owner: UncheckedAccount<'info>,
    #[account(mut, seeds = [CONFIG_SEED], bump = config.bump)]
    pub config: Account<'info, Config>,
    #[account(
        mut,
        seeds = [PROFILE_SEED, owner.key().as_ref()],
        bump = profile.bump,
        has_one = owner
    )]
    pub profile: Account<'info, Profile>,
}

pub fn handle_reap(ctx: Context<Reap>) -> Result<()> {
    let today = day_of(Clock::get()?.unix_timestamp);
    let config = &mut ctx.accounts.config;
    let profile = &mut ctx.accounts.profile;
    require!(profile.active, ClockInError::PositionInactive);

    let bound = profile.settle_bound(today);
    require!(bound > profile.settled_day, ClockInError::NothingToReap);
    profile.settle_through(config, bound);
    Ok(())
}
```

Déclarer `pub mod reap; pub use reap::*;` et dans `#[program]` :

```rust
    pub fn reap(ctx: Context<Reap>) -> Result<()> {
        instructions::reap::handle_reap(ctx)
    }
```

- [ ] **Step 4 : Étendre le harness**

```rust
    /// Appelé par l'admin, qui n'est ni le propriétaire ni un bénéficiaire :
    /// c'est bien un tiers qui déclenche le règlement.
    pub fn reap(&mut self, owner: &Pubkey) -> TransactionResult {
        let instruction = Instruction {
            program_id: clockin::id(),
            accounts: clockin::accounts::Reap {
                caller: self.admin.pubkey(),
                owner: *owner,
                config: self.config,
                profile: self.profile_address(owner),
            }
            .to_account_metas(None),
            data: clockin::instruction::Reap {}.data(),
        };
        let admin = self.admin.insecure_clone();
        self.send(&[instruction], &[&admin])
    }
```

- [ ] **Step 5 : Lancer les tests**

Run : `cd program && anchor build --arch v0 && cargo test --test test_reap --test test_decay`
Expected : PASS — 4 + 3 tests.

- [ ] **Step 6 : Commit**

```bash
git add program/
git commit -m "feat(program): reap permissionless sans double-decay"
```

---

### Task 8 : `request_exit` et `cancel_exit`

La sortie est différée de 48 h pour qu'on ne puisse pas retirer sa mise du jour au
lendemain afin d'éviter une absence (§6.3). Pendant l'attente, la position reste
active : l'utilisateur peut encore publier et éviter le decay.

**Files:**
- Create: `program/programs/clockin/src/instructions/exit.rs`
- Modify: `src/instructions/mod.rs`, `src/lib.rs`, `tests/common/mod.rs`
- Test: `program/programs/clockin/tests/test_exit.rs`

**Interfaces:**
- Consumes : `Profile::settle_bound`, `Profile::settle_through`, `Ctx::check_in`.
- Produces : `request_exit()`, `cancel_exit()`, helpers `Ctx::request_exit(&User)`, `Ctx::cancel_exit(&User)`, `Ctx::warp_seconds(i64)` (déjà présent), horloge de test figée.

- [ ] **Step 1 : Figer l'horloge du harness**

Dans `Ctx::empty()` de `tests/common/mod.rs`, juste après `svm.add_program(...)` :

```rust
        // Horloge figée au 2026-09-10 à 12 h UTC. La position de l'heure dans la
        // journée décide quel jour est « entièrement terminé avant le déblocage »
        // (§6.3) : la laisser dépendre de l'heure réelle rendrait ces tests instables.
        let mut clock = svm.get_sysvar::<Clock>();
        clock.unix_timestamp = 1_789_041_600;
        svm.set_sysvar(&clock);
```

- [ ] **Step 2 : Écrire les tests**

`program/programs/clockin/tests/test_exit.rs` :

```rust
mod common;

use common::{Ctx, DAY, SKR};

#[test]
fn request_exit_freezes_the_unlock_time() {
    let mut ctx = Ctx::new();
    let user = ctx.new_user();
    ctx.stake(&user, 40 * SKR).unwrap();

    let requested_at = ctx.now();
    ctx.request_exit(&user).unwrap();

    let profile = ctx.profile_state(&user.profile);
    assert_eq!(profile.exit_requested_at, requested_at);
    assert_eq!(profile.exit_unlock_at, requested_at + 172_800);
    assert!(profile.active, "la position reste active pendant l'attente");
}

#[test]
fn a_config_change_does_not_move_an_open_exit() {
    let mut ctx = Ctx::new();
    let user = ctx.new_user();
    ctx.stake(&user, 40 * SKR).unwrap();
    ctx.request_exit(&user).unwrap();
    let frozen = ctx.profile_state(&user.profile).exit_unlock_at;

    ctx.update_config(|params| params.withdrawal_delay_seconds = 10 * DAY).unwrap();

    assert_eq!(ctx.profile_state(&user.profile).exit_unlock_at, frozen);
}

#[test]
fn a_second_request_is_refused() {
    let mut ctx = Ctx::new();
    let user = ctx.new_user();
    ctx.stake(&user, 40 * SKR).unwrap();
    ctx.request_exit(&user).unwrap();

    assert!(ctx.request_exit(&user).is_err());
}

#[test]
fn check_in_is_possible_while_waiting_and_avoids_the_decay() {
    let mut ctx = Ctx::new();
    let user = ctx.new_user();
    ctx.stake(&user, 40 * SKR).unwrap();
    ctx.check_in(&user).unwrap();
    ctx.request_exit(&user).unwrap();

    ctx.warp_days(1);
    ctx.check_in(&user).unwrap();

    assert_eq!(ctx.profile_state(&user.profile).staked, 40 * SKR, "aucun jour manqué");
}

#[test]
fn check_in_after_the_unlock_is_refused() {
    let mut ctx = Ctx::new();
    let user = ctx.new_user();
    ctx.stake(&user, 40 * SKR).unwrap();
    ctx.request_exit(&user).unwrap();

    ctx.warp_days(2);
    assert!(ctx.check_in(&user).is_err(), "la position n'est plus exposée");
}

#[test]
fn cancelling_before_the_unlock_reopens_the_position() {
    let mut ctx = Ctx::new();
    let user = ctx.new_user();
    ctx.stake(&user, 40 * SKR).unwrap();
    ctx.request_exit(&user).unwrap();

    ctx.warp_seconds(3_600);
    ctx.cancel_exit(&user).unwrap();

    let profile = ctx.profile_state(&user.profile);
    assert_eq!(profile.exit_unlock_at, 0);
    assert_eq!(profile.exit_requested_at, 0);
    assert!(profile.active);

    // Une nouvelle demande repart de zéro.
    let now = ctx.now();
    ctx.request_exit(&user).unwrap();
    assert_eq!(ctx.profile_state(&user.profile).exit_unlock_at, now + 172_800);
}

#[test]
fn cancelling_after_the_unlock_is_refused() {
    let mut ctx = Ctx::new();
    let user = ctx.new_user();
    ctx.stake(&user, 40 * SKR).unwrap();
    ctx.request_exit(&user).unwrap();

    ctx.warp_days(2);
    assert!(ctx.cancel_exit(&user).is_err());
}

#[test]
fn cancelling_never_gives_back_what_the_decay_already_took() {
    let mut ctx = Ctx::new();
    let user = ctx.new_user();
    ctx.stake(&user, 40 * SKR).unwrap();
    ctx.check_in(&user).unwrap();

    ctx.warp_days(2); // un jour entier manqué
    ctx.request_exit(&user).unwrap();
    let after_request = ctx.profile_state(&user.profile).staked;
    assert_eq!(after_request, 30 * SKR, "request_exit règle d'abord les jours manqués");

    ctx.cancel_exit(&user).unwrap();
    assert_eq!(ctx.profile_state(&user.profile).staked, 30 * SKR);
}

#[test]
fn staking_during_an_exit_is_refused() {
    let mut ctx = Ctx::new();
    let user = ctx.new_user();
    ctx.stake(&user, 40 * SKR).unwrap();
    ctx.request_exit(&user).unwrap();

    assert!(ctx.stake(&user, 5 * SKR).is_err());
}
```

- [ ] **Step 3 : Vérifier l'échec**

Run : `cd program && cargo test --test test_exit`
Expected : FAIL — `no method named 'request_exit'`.

- [ ] **Step 4 : Implémenter**

`program/programs/clockin/src/instructions/exit.rs` :

```rust
use anchor_lang::prelude::*;

use crate::{constants::*, economy::day_of, error::ClockInError, state::{Config, Profile}};

#[derive(Accounts)]
pub struct ExitRequest<'info> {
    pub owner: Signer<'info>,
    #[account(mut, seeds = [CONFIG_SEED], bump = config.bump)]
    pub config: Account<'info, Config>,
    #[account(
        mut,
        seeds = [PROFILE_SEED, owner.key().as_ref()],
        bump = profile.bump,
        has_one = owner
    )]
    pub profile: Account<'info, Profile>,
}

pub fn handle_request_exit(ctx: Context<ExitRequest>) -> Result<()> {
    let clock = Clock::get()?;
    let today = day_of(clock.unix_timestamp);
    let config = &mut ctx.accounts.config;
    let profile = &mut ctx.accounts.profile;
    require!(profile.active, ClockInError::PositionInactive);
    require!(profile.exit_unlock_at == 0, ClockInError::ExitAlreadyRequested);

    // Régler d'abord : on ne sort pas d'une position en dissimulant ses absences.
    let bound = profile.settle_bound(today);
    profile.settle_through(config, bound);

    profile.exit_requested_at = clock.unix_timestamp;
    // Délai figé pour cette demande, même si la configuration change ensuite (§6.3).
    profile.exit_unlock_at = clock
        .unix_timestamp
        .checked_add(config.withdrawal_delay_seconds)
        .ok_or(ClockInError::InvalidConfigParam)?;
    Ok(())
}

pub fn handle_cancel_exit(ctx: Context<ExitRequest>) -> Result<()> {
    let clock = Clock::get()?;
    let profile = &mut ctx.accounts.profile;
    require!(profile.exit_unlock_at > 0, ClockInError::NoExitRequested);
    require!(clock.unix_timestamp < profile.exit_unlock_at, ClockInError::ExitUnlocked);
    // Annuler ne restitue jamais les pertes déjà appliquées (§6.3).
    profile.exit_requested_at = 0;
    profile.exit_unlock_at = 0;
    Ok(())
}
```

Déclarer `pub mod exit; pub use exit::*;` et dans `#[program]` :

```rust
    pub fn request_exit(ctx: Context<ExitRequest>) -> Result<()> {
        instructions::exit::handle_request_exit(ctx)
    }

    pub fn cancel_exit(ctx: Context<ExitRequest>) -> Result<()> {
        instructions::exit::handle_cancel_exit(ctx)
    }
```

- [ ] **Step 5 : Étendre le harness**

```rust
    pub fn request_exit(&mut self, user: &User) -> TransactionResult {
        self.exit_request_instruction(user, clockin::instruction::RequestExit {}.data())
    }

    pub fn cancel_exit(&mut self, user: &User) -> TransactionResult {
        self.exit_request_instruction(user, clockin::instruction::CancelExit {}.data())
    }

    fn exit_request_instruction(&mut self, user: &User, data: Vec<u8>) -> TransactionResult {
        let instruction = Instruction {
            program_id: clockin::id(),
            accounts: clockin::accounts::ExitRequest {
                owner: user.pubkey(),
                config: self.config,
                profile: user.profile,
            }
            .to_account_metas(None),
            data,
        };
        let signer = user.keypair.insecure_clone();
        self.send(&[instruction], &[&signer])
    }
```

- [ ] **Step 6 : Lancer les tests**

Run : `cd program && anchor build --arch v0 && cargo test --test test_exit`
Expected : PASS, 9 tests.

- [ ] **Step 7 : Commit**

```bash
git add program/
git commit -m "feat(program): demande et annulation de sortie differee"
```

---

### Task 9 : `finalize_exit`

Le seul autre endroit où des tokens quittent le vault. Permissionless, pour qu'un
keeper puisse finaliser à la place du propriétaire, mais le transfert est **toujours**
adressé au compte de tokens du propriétaire.

**Files:**
- Modify: `program/programs/clockin/src/instructions/exit.rs`, `src/lib.rs`, `tests/common/mod.rs`, `tests/test_exit.rs`

**Interfaces:**
- Consumes : `Profile::settle_through`, `day_of`, `Config.vault`.
- Produces : `finalize_exit()`, helper `Ctx::finalize_exit(&Pubkey, &Pubkey)` (propriétaire, compte de tokens destinataire).

- [ ] **Step 1 : Écrire les tests**

Ajouter à `program/programs/clockin/tests/test_exit.rs` :

```rust
#[test]
fn finalizing_before_the_unlock_is_refused() {
    let mut ctx = Ctx::new();
    let user = ctx.new_user();
    ctx.stake(&user, 40 * SKR).unwrap();
    ctx.request_exit(&user).unwrap();

    ctx.warp_seconds(172_800 - 60);
    let owner = user.pubkey();
    let token_account = user.token_account;
    assert!(ctx.finalize_exit(&owner, &token_account).is_err());
}

#[test]
fn the_spec_example_costs_exactly_one_missed_day() {
    // Check-in lundi, demande lundi, aucun autre check-in, déblocage mercredi
    // à la même heure : seul mardi est manqué (§6.3).
    let mut ctx = Ctx::new();
    let user = ctx.new_user();
    ctx.stake(&user, 40 * SKR).unwrap();
    ctx.check_in(&user).unwrap();
    ctx.request_exit(&user).unwrap();

    ctx.warp_days(2);
    let owner = user.pubkey();
    let token_account = user.token_account;
    ctx.finalize_exit(&owner, &token_account).unwrap();

    let profile = ctx.profile_state(&user.profile);
    assert_eq!(profile.staked, 0);
    assert!(!profile.active);
    assert_eq!(profile.streak, 0);
    assert_eq!(profile.exit_unlock_at, 0);
    // 60 SKR restants au wallet + 30 SKR récupérés (40 décimés une fois).
    assert_eq!(ctx.token_balance(&token_account), 90 * SKR);
    assert_eq!(ctx.vault_balance(), 0);
    assert_eq!(ctx.config_state().pool_balance, 10 * SKR);
}

#[test]
fn checking_in_during_the_wait_costs_nothing_at_all() {
    let mut ctx = Ctx::new();
    let user = ctx.new_user();
    ctx.stake(&user, 40 * SKR).unwrap();
    ctx.check_in(&user).unwrap();
    ctx.request_exit(&user).unwrap();

    ctx.warp_days(1);
    ctx.check_in(&user).unwrap();
    ctx.warp_days(1);

    let owner = user.pubkey();
    let token_account = user.token_account;
    ctx.finalize_exit(&owner, &token_account).unwrap();

    assert_eq!(ctx.token_balance(&token_account), 100 * SKR, "sortie sans perte");
    assert_eq!(ctx.config_state().pool_balance, 0);
}

#[test]
fn a_late_finalization_adds_no_extra_decay() {
    let mut ctx = Ctx::new();
    let user = ctx.new_user();
    ctx.stake(&user, 40 * SKR).unwrap();
    ctx.check_in(&user).unwrap();
    ctx.request_exit(&user).unwrap();

    ctx.warp_days(9); // sept jours après le déblocage
    let owner = user.pubkey();
    let token_account = user.token_account;
    ctx.finalize_exit(&owner, &token_account).unwrap();

    assert_eq!(ctx.token_balance(&token_account), 90 * SKR, "toujours un seul jour manqué");
}

#[test]
fn a_late_reap_adds_no_extra_decay_either() {
    let mut ctx = Ctx::new();
    let user = ctx.new_user();
    ctx.stake(&user, 40 * SKR).unwrap();
    ctx.check_in(&user).unwrap();
    ctx.request_exit(&user).unwrap();

    ctx.warp_days(9);
    let owner = user.pubkey();
    ctx.reap(&owner).unwrap();
    assert_eq!(ctx.profile_state(&user.profile).staked, 30 * SKR);
    assert!(ctx.reap(&owner).is_err(), "plus rien à régler après le déblocage");
}

#[test]
fn a_third_party_can_finalize_but_only_the_owner_is_paid() {
    let mut ctx = Ctx::new();
    let user = ctx.new_user();
    let keeper = ctx.new_user();
    ctx.stake(&user, 40 * SKR).unwrap();
    ctx.request_exit(&user).unwrap();
    ctx.warp_days(2);

    let owner = user.pubkey();
    let owner_token = user.token_account;
    let keeper_token = keeper.token_account;
    let keeper_before = ctx.token_balance(&keeper_token);

    ctx.finalize_exit_as(&keeper.keypair.insecure_clone(), &owner, &owner_token).unwrap();

    assert_eq!(ctx.token_balance(&owner_token), 100 * SKR);
    assert_eq!(ctx.token_balance(&keeper_token), keeper_before, "le keeper ne touche rien");
}

#[test]
fn finalizing_into_someone_elses_token_account_is_refused() {
    let mut ctx = Ctx::new();
    let user = ctx.new_user();
    let thief = ctx.new_user();
    ctx.stake(&user, 40 * SKR).unwrap();
    ctx.request_exit(&user).unwrap();
    ctx.warp_days(2);

    let owner = user.pubkey();
    let thief_token = thief.token_account;
    assert!(ctx.finalize_exit(&owner, &thief_token).is_err());
}

#[test]
fn reopening_a_position_after_an_exit_starts_a_fresh_streak() {
    let mut ctx = Ctx::new();
    let user = ctx.new_user();
    ctx.stake(&user, 40 * SKR).unwrap();
    ctx.check_in(&user).unwrap();
    ctx.request_exit(&user).unwrap();
    ctx.warp_days(2);
    let owner = user.pubkey();
    let token_account = user.token_account;
    ctx.finalize_exit(&owner, &token_account).unwrap();

    ctx.stake(&user, 20 * SKR).unwrap();
    let profile = ctx.profile_state(&user.profile);
    assert!(profile.active);
    assert_eq!(profile.staked, 20 * SKR);
    assert_eq!(profile.streak, 0);
    assert_eq!(profile.settled_day, ctx.today() - 1);

    ctx.check_in(&user).unwrap();
    assert_eq!(ctx.profile_state(&user.profile).streak, 1);
}
```

- [ ] **Step 2 : Vérifier l'échec**

Run : `cd program && cargo test --test test_exit`
Expected : FAIL — `no method named 'finalize_exit'`.

- [ ] **Step 3 : Implémenter**

Ajouter à `program/programs/clockin/src/instructions/exit.rs` :

```rust
use anchor_spl::token::{transfer_checked, Mint, Token, TokenAccount, TransferChecked};

#[derive(Accounts)]
pub struct FinalizeExit<'info> {
    /// Permissionless : un keeper peut finaliser pour le propriétaire.
    pub caller: Signer<'info>,
    /// CHECK: propriétaire de la position, lié au profil par `has_one`.
    pub owner: UncheckedAccount<'info>,
    #[account(mut, seeds = [CONFIG_SEED], bump = config.bump)]
    pub config: Account<'info, Config>,
    #[account(
        mut,
        seeds = [PROFILE_SEED, owner.key().as_ref()],
        bump = profile.bump,
        has_one = owner
    )]
    pub profile: Account<'info, Profile>,
    #[account(address = config.skr_mint)]
    pub skr_mint: Account<'info, Mint>,
    /// Le versement est toujours adressé au propriétaire, jamais à l'appelant.
    #[account(
        mut,
        constraint = owner_token_account.owner == owner.key() @ ClockInError::InvalidOwnerTokenAccount,
        constraint = owner_token_account.mint == config.skr_mint @ ClockInError::InvalidOwnerTokenAccount,
    )]
    pub owner_token_account: Account<'info, TokenAccount>,
    #[account(mut, address = config.vault)]
    pub vault: Account<'info, TokenAccount>,
    pub token_program: Program<'info, Token>,
}

pub fn handle_finalize_exit(ctx: Context<FinalizeExit>) -> Result<()> {
    let clock = Clock::get()?;
    {
        let config = &mut ctx.accounts.config;
        let profile = &mut ctx.accounts.profile;
        require!(profile.exit_unlock_at > 0, ClockInError::NoExitRequested);
        require!(clock.unix_timestamp >= profile.exit_unlock_at, ClockInError::ExitLocked);
        // Dernière journée pénalisable : celle entièrement terminée avant le
        // déblocage. Une finalisation tardive ne coûte donc rien de plus (§6.3).
        let bound = day_of(profile.exit_unlock_at) - 1;
        profile.settle_through(config, bound);
    }

    let amount = ctx.accounts.profile.staked;
    if amount > 0 {
        let bump = ctx.accounts.config.bump;
        let decimals = ctx.accounts.skr_mint.decimals;
        let seeds: &[&[u8]] = &[CONFIG_SEED, &[bump]];
        transfer_checked(
            CpiContext::new_with_signer(
                ctx.accounts.token_program.to_account_info(),
                TransferChecked {
                    from: ctx.accounts.vault.to_account_info(),
                    mint: ctx.accounts.skr_mint.to_account_info(),
                    to: ctx.accounts.owner_token_account.to_account_info(),
                    authority: ctx.accounts.config.to_account_info(),
                },
                &[seeds],
            ),
            amount,
            decimals,
        )?;
    }

    let profile = &mut ctx.accounts.profile;
    profile.staked = 0;
    profile.active = false;
    profile.streak = 0;
    profile.exit_requested_at = 0;
    profile.exit_unlock_at = 0;
    Ok(())
}
```

Dans `#[program]` :

```rust
    pub fn finalize_exit(ctx: Context<FinalizeExit>) -> Result<()> {
        instructions::exit::handle_finalize_exit(ctx)
    }
```

- [ ] **Step 4 : Étendre le harness**

```rust
    pub fn finalize_exit(&mut self, owner: &Pubkey, owner_token_account: &Pubkey) -> TransactionResult {
        let admin = self.admin.insecure_clone();
        self.finalize_exit_as(&admin, owner, owner_token_account)
    }

    pub fn finalize_exit_as(
        &mut self,
        caller: &Keypair,
        owner: &Pubkey,
        owner_token_account: &Pubkey,
    ) -> TransactionResult {
        let instruction = Instruction {
            program_id: clockin::id(),
            accounts: clockin::accounts::FinalizeExit {
                caller: caller.pubkey(),
                owner: *owner,
                config: self.config,
                profile: self.profile_address(owner),
                skr_mint: self.mint,
                owner_token_account: *owner_token_account,
                vault: self.vault,
                token_program: spl_token::ID,
            }
            .to_account_metas(None),
            data: clockin::instruction::FinalizeExit {}.data(),
        };
        let caller = caller.insecure_clone();
        self.send(&[instruction], &[&caller])
    }
```

- [ ] **Step 5 : Lancer toute la suite**

Run : `cd program && anchor build --arch v0 && cargo test`
Expected : PASS — tous les fichiers de test, dont les 17 de `test_exit`.

- [ ] **Step 6 : Commit**

```bash
git add program/
git commit -m "feat(program): finalize_exit permissionless verse au proprietaire"
```

---

### Task 10 : Invariant de conservation et déploiement devnet

Le test qui garde tout le reste honnête : après n'importe quelle séquence,
`Σ staked + pool_balance == solde du vault`. Puis le déploiement devnet et l'export
de l'IDL dont dépend le plan 02.

**Files:**
- Create: `program/programs/clockin/tests/test_conservation.rs`
- Create: `program/scripts/deploy-devnet.sh`
- Modify: `program/Anchor.toml`, `README.md`

**Interfaces:**
- Consumes : tout le harness.
- Produces : `program/target/idl/clockin.json` exporté, adresse de `Config` et du mint de test documentées.

- [ ] **Step 1 : Écrire le test de conservation**

`program/programs/clockin/tests/test_conservation.rs` :

```rust
mod common;

use common::{Ctx, User, SKR};

/// Σ staked + pool_balance == solde du vault, après chaque étape.
fn assert_conservation(ctx: &Ctx, users: &[&User]) {
    let total: u64 = users
        .iter()
        .map(|user| ctx.profile_state(&user.profile).staked)
        .sum();
    assert_eq!(
        total + ctx.config_state().pool_balance,
        ctx.vault_balance(),
        "le vault doit contenir exactement les mises plus le pool"
    );
}

#[test]
fn a_full_week_with_a_regular_and_a_lapsed_member_conserves_every_token() {
    let mut ctx = Ctx::new();
    ctx.seed_pool(50 * SKR).unwrap();

    let regular = ctx.new_user();
    let lapsed = ctx.new_user();
    ctx.stake(&regular, 60 * SKR).unwrap();
    ctx.stake(&lapsed, 60 * SKR).unwrap();
    assert_conservation(&ctx, &[&regular, &lapsed]);

    for _ in 0..6 {
        ctx.check_in(&regular).unwrap();
        assert_conservation(&ctx, &[&regular, &lapsed]);
        ctx.warp_days(1);
    }

    // Le retardataire est réglé par un tiers puis revient.
    let lapsed_owner = lapsed.pubkey();
    ctx.reap(&lapsed_owner).unwrap();
    assert_conservation(&ctx, &[&regular, &lapsed]);

    let regular_profile = ctx.profile_state(&regular.profile);
    let lapsed_profile = ctx.profile_state(&lapsed.profile);
    assert!(regular_profile.staked > 60 * SKR, "la discipline est payée");
    assert!(lapsed_profile.staked < 60 * SKR, "le relâchement paye");
    assert_eq!(regular_profile.streak, 6);
    assert_eq!(lapsed_profile.streak, 0);

    // Les deux sortent : le vault se vide exactement.
    ctx.request_exit(&regular).unwrap();
    ctx.request_exit(&lapsed).unwrap();
    ctx.warp_days(2);
    let regular_owner = regular.pubkey();
    let regular_token = regular.token_account;
    let lapsed_token = lapsed.token_account;
    ctx.finalize_exit(&regular_owner, &regular_token).unwrap();
    ctx.finalize_exit(&lapsed_owner, &lapsed_token).unwrap();

    assert_conservation(&ctx, &[&regular, &lapsed]);
    assert_eq!(
        ctx.vault_balance(),
        ctx.config_state().pool_balance,
        "il ne reste au vault que le pool non distribué"
    );
}
```

- [ ] **Step 2 : Lancer**

Run : `cd program && anchor build --arch v0 && cargo test`
Expected : PASS pour toute la suite. Si l'invariant casse, le coupable est le dernier
handler qui a touché `pool_balance` ou `staked` sans passer par `settle_through`.

- [ ] **Step 3 : Écrire le script de déploiement devnet**

`program/scripts/deploy-devnet.sh` :

```bash
#!/usr/bin/env bash
# Déploie clockin sur devnet, crée le mint de test SKR, initialise la
# configuration et amorce le pool. Idempotent tant que Config existe déjà.
set -euo pipefail

cd "$(dirname "$0")/.."

: "${PUBLICATION_AUTHORITY:?adresse publique de l'autorité de publication requise}"

solana config set --url devnet
anchor build --arch v0
anchor deploy --provider.cluster devnet

echo "IDL exporté dans target/idl/clockin.json"
echo "Programme : $(solana address -k target/deploy/clockin-keypair.json)"
echo "Autorité de publication : ${PUBLICATION_AUTHORITY}"
echo
echo "Étapes manuelles restantes, à exécuter une seule fois :"
echo "  1. Créer le mint de test avec le PDA Config comme autorité de mint."
echo "  2. Appeler initialize_config avec les paramètres de la feuille de route."
echo "  3. Appeler seed_pool pour amorcer le pool."
echo "Ces trois appels sont scriptés côté app (plan 02, tâche 8) ou via anchor run."
```

```bash
chmod +x program/scripts/deploy-devnet.sh
```

- [ ] **Step 4 : Pointer Anchor sur devnet**

Dans `program/Anchor.toml` :

```toml
[programs.devnet]
clockin = "7TgCk9XekpU88Tiewd5VKfhmVJQyxNRR8915pzqU3rG1"

[provider]
cluster = "devnet"
wallet = "~/.config/solana/id.json"
```

- [ ] **Step 5 : Déployer et noter les adresses**

```bash
solana airdrop 5 --url devnet   # si le solde est insuffisant
PUBLICATION_AUTHORITY=$(solana address -k keys/dev-publication-authority.json) \
  ./program/scripts/deploy-devnet.sh
```

La clé `keys/dev-publication-authority.json` est créée par le plan 02, tâche 7, et
n'est **jamais commitée** (ajouter `keys/` au `.gitignore` à la racine).

- [ ] **Step 6 : Documenter dans le README**

Ajouter une section « Programme on-chain » au `README.md` : identifiant du programme,
adresse du mint de test devnet, adresse de `Config`, paramètres en vigueur, et la
commande `anchor build --arch v0 && cargo test` avec le nombre de tests.

- [ ] **Step 7 : Commit**

```bash
git add program/ README.md .gitignore
git commit -m "test(program): invariant de conservation et deploiement devnet"
```

---

## Self-Review

**Couverture de la spec :** §5 comptes → tâches 2 et 6 ; §6.1 check_in → tâche 6 ;
§6.2 amorçage → tâche 3 ; §6.3 sortie → tâches 8 et 9 ; §6.4 reap → tâche 7 ;
§6.5 tip → **hors v1, assumé** (stretch, §6.5 de la spec) ; §10 faucet → tâche 4 ;
§11 liste de tests → chaque puce a un test nommé dans les tâches 5 à 10.

**Écarts assumés, déjà arbitrés dans la feuille de route :** `cid` → `blob_ref` (D1),
autorisation par co-signature (D4), `day` en argument vérifié (D6), aucune sanction
de modération on-chain (D3), pas de bonus au reaper (§6.4 phase 3), pas de `tip`.
