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

    // Le retardataire est réglé par un tiers.
    let lapsed_owner = lapsed.pubkey();
    ctx.reap(&lapsed_owner).unwrap();
    assert_conservation(&ctx, &[&regular, &lapsed]);

    let regular_profile = ctx.profile_state(&regular.profile);
    let lapsed_profile = ctx.profile_state(&lapsed.profile);
    assert!(regular_profile.staked > 60 * SKR, "la discipline est payée");
    assert!(lapsed_profile.staked < 60 * SKR, "le relâchement paye");
    assert_eq!(regular_profile.streak, 6);
    assert_eq!(lapsed_profile.streak, 0);

    // Les deux sortent : le vault ne garde plus que le pool non distribué.
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
