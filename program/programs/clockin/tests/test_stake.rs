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

    // Miser n'est pas publier : le jour J est manqué lui aussi, comme J+1 et J+2.
    // Une position ouverte le jour J l'est avec settled_day = J - 1 (§5).
    ctx.warp_days(3);
    ctx.stake(&user, 10 * SKR).unwrap();

    // 40 SKR décimés trois fois à 25 % = 16,875 SKR, plus 10 SKR intacts.
    let profile = ctx.profile_state(&user.profile);
    assert_eq!(profile.staked, 26_875_000_000);
    assert_eq!(ctx.pooled(), 23_125_000_000);
    assert_eq!(profile.settled_day, ctx.today() - 1);
    assert_eq!(ctx.vault_balance(), 50 * SKR, "les tokens ne bougent jamais lors d'un decay");
}

#[test]
fn staking_zero_is_refused() {
    let mut ctx = Ctx::new();
    let user = ctx.new_user();
    assert!(ctx.stake(&user, 0).is_err());
}
