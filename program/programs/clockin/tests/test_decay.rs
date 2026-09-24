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

    // 40 SKR décimés de 25 % font 30 SKR. Personne n'a publié le jour manqué :
    // les 10 SKR perdus partent au pool du jour courant.
    let profile = ctx.profile_state(&user.profile);
    assert_eq!(profile.staked, 30 * SKR);
    assert_eq!(profile.streak, 1, "le streak repart de zéro puis vaut 1");
    assert_eq!(ctx.pooled(), 10 * SKR);
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
    let owner = user.pubkey();
    ctx.reap(&owner).unwrap();

    assert_eq!(ctx.profile_state(&user.profile).staked, 0);
    assert_eq!(ctx.pooled(), 80 * SKR);
    assert!(ctx.check_in(&user).is_err(), "il faut recharger pour rejouer");
}
