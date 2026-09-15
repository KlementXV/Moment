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
    // Aucun decay supplémentaire : seule la récompense, prise dans le pool que
    // ce même profil vient d'alimenter, s'ajoute.
    let pool_before_reward = 40 * SKR - after_reap;
    let expected_reward = (after_reap / 100).min(SKR).min(pool_before_reward);
    assert_eq!(profile.staked, after_reap + expected_reward);
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
