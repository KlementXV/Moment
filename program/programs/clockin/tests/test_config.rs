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
