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
    assert_eq!(config.min_stake, MIN_STAKE);
    assert_eq!(config.pool_close_delay_seconds, 21_600);
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
    let today = ctx.today();
    assert_eq!(ctx.day_pool_state(today).unwrap().penalties, 500 * SKR);
}

#[test]
fn update_config_recalibrates_without_touching_the_pool() {
    let mut ctx = Ctx::new();
    ctx.seed_pool(500 * SKR).unwrap();

    ctx.update_config(|params| {
        params.decay_bps = 5000;
    })
    .unwrap();

    let config = ctx.config_state();
    assert_eq!(config.decay_bps, 5000);
    let today = ctx.today();
    assert_eq!(
        ctx.day_pool_state(today).unwrap().penalties,
        500 * SKR,
        "le pool ne doit pas bouger"
    );
}

#[test]
fn the_close_delay_is_fixed_once_deployed() {
    let mut ctx = Ctx::new();
    let result = ctx.update_config(|params| params.pool_close_delay_seconds = 3_600);
    assert!(result.is_err());
    assert_eq!(ctx.config_state().pool_close_delay_seconds, 21_600);
}

#[test]
fn initialize_config_refuses_a_close_delay_under_an_hour() {
    let mut ctx = Ctx::empty();
    assert!(ctx.initialize_config_with(|params| params.pool_close_delay_seconds = 1_800).is_err());
}

#[test]
fn update_config_refuses_a_close_delay_of_a_full_day() {
    let mut ctx = Ctx::new();
    let result = ctx.update_config(|params| params.pool_close_delay_seconds = 86_400);
    assert!(result.is_err(), "la clôture doit tomber avant la fin du lendemain");
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

#[test]
fn initialize_config_requires_the_upgrade_authority() {
    let mut ctx = Ctx::empty();
    let attacker = Keypair::new();
    ctx.svm.airdrop(&attacker.pubkey(), 10_000_000_000).unwrap();
    let admin = std::mem::replace(&mut ctx.admin, attacker);
    assert!(ctx.initialize_config_with(|_| {}).is_err());
    assert!(ctx.svm.get_account(&ctx.config).is_none());
    ctx.admin = admin;
    ctx.initialize_config_with(|_| {}).unwrap();
    assert_eq!(ctx.config_state().admin, ctx.admin.pubkey());
}

#[test]
fn an_immutable_program_cannot_be_initialized() {
    let mut ctx = Ctx::empty();
    let address = anchor_lang::prelude::Pubkey::find_program_address(
        &[moment::id().as_ref()],
        &anchor_lang::solana_program::bpf_loader_upgradeable::ID,
    ).0;
    let mut account = ctx.svm.get_account(&address).unwrap();
    account.data[12] = 0; // None
    ctx.svm.set_account(address, account).unwrap();
    assert!(ctx.initialize_config_with(|_| {}).is_err());
}
