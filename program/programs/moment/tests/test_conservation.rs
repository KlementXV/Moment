mod common;

use moment::economy::share;
use common::{Ctx, User, SKR};

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

#[test]
fn a_full_week_with_a_regular_and_a_lapsed_member_conserves_every_token() {
    let mut ctx = Ctx::new();
    ctx.seed_pool(50 * SKR).unwrap();

    let regular = ctx.new_user();
    let lapsed = ctx.new_user();
    ctx.stake(&regular, 60 * SKR).unwrap();
    ctx.stake(&lapsed, 60 * SKR).unwrap();
    assert_solvent(&ctx, &[&regular, &lapsed]);

    for _ in 0..6 {
        ctx.check_in(&regular).unwrap();
        assert_solvent(&ctx, &[&regular, &lapsed]);
        ctx.warp_days(1);
    }

    let lapsed_owner = lapsed.pubkey();
    ctx.reap(&lapsed_owner).unwrap();
    assert_solvent(&ctx, &[&regular, &lapsed]);

    let regular_profile = ctx.profile_state(&regular.profile);
    let lapsed_profile = ctx.profile_state(&lapsed.profile);
    assert!(regular_profile.staked > 60 * SKR, "la discipline est payée");
    assert!(lapsed_profile.staked < 60 * SKR, "le relâchement paye");
    assert_eq!(regular_profile.streak, 6);
    assert_eq!(lapsed_profile.streak, 0);

    ctx.request_exit(&regular).unwrap();
    ctx.request_exit(&lapsed).unwrap();
    ctx.warp_days(2);
    let regular_owner = regular.pubkey();
    let regular_token = regular.token_account;
    let lapsed_token = lapsed.token_account;
    ctx.finalize_exit(&regular_owner, &regular_token).unwrap();
    ctx.finalize_exit(&lapsed_owner, &lapsed_token).unwrap();

    assert_solvent(&ctx, &[&regular, &lapsed]);
    assert!(
        ctx.vault_balance() <= ctx.pooled(),
        "il ne reste que la poussière et les pools non distribués"
    );
}
