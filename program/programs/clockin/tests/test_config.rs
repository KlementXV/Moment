mod common;

use common::{Ctx, MIN_STAKE, SKR};
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
