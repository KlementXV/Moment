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
    assert_eq!(profile.pending_days, [-1, -1]);
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
