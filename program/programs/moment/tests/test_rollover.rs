mod common;

use common::{Ctx, SKR};

const HOUR: i64 = 3_600;

fn ctx() -> Ctx {
    let mut ctx = Ctx::empty();
    ctx.initialize_config_with(|params| params.decay_bps = 1000).unwrap();
    ctx
}

#[test]
fn a_pool_without_publishers_rolls_over_to_today_after_closure() {
    let mut ctx = ctx();
    let a = ctx.new_user();
    ctx.stake(&a, 100 * SKR).unwrap();
    let d0 = ctx.today();
    ctx.seed_pool(20 * SKR).unwrap();

    ctx.warp_to_next(7 * HOUR);
    let d1 = ctx.today();
    ctx.roll_over(d0).unwrap();
    assert_eq!(ctx.day_pool_state(d0).unwrap().penalties, 0);
    assert_eq!(ctx.day_pool_state(d1).unwrap().penalties, 20 * SKR);

    ctx.check_in(&a).unwrap();
    ctx.warp_days(1);
    ctx.check_in(&a).unwrap();
    assert_eq!(ctx.profile_state(&a.profile).staked, 90 * SKR + 30 * SKR);
    assert_eq!(ctx.vault_balance(), 120 * SKR, "plus rien de bloqué");
}

#[test]
fn a_pool_cannot_roll_over_before_its_closure() {
    let mut ctx = ctx();
    let d0 = ctx.today();
    ctx.seed_pool(20 * SKR).unwrap();
    ctx.warp_to_next(3 * HOUR);
    assert!(ctx.roll_over(d0).is_err());
    assert_eq!(ctx.day_pool_state(d0).unwrap().penalties, 20 * SKR);
}

#[test]
fn a_pool_with_publishers_never_rolls_over() {
    let mut ctx = ctx();
    let a = ctx.new_user();
    ctx.stake(&a, 100 * SKR).unwrap();
    let d0 = ctx.today();
    ctx.seed_pool(20 * SKR).unwrap();
    ctx.check_in(&a).unwrap();
    ctx.warp_to_next(7 * HOUR);
    assert!(ctx.roll_over(d0).is_err(), "sa part appartient à ses publieurs");
    assert_eq!(ctx.day_pool_state(d0).unwrap().penalties, 20 * SKR);
}

#[test]
fn an_empty_pool_is_not_rolled_over() {
    let mut ctx = ctx();
    let d0 = ctx.today();
    ctx.open_day_pool().unwrap();
    ctx.warp_to_next(7 * HOUR);
    assert!(ctx.roll_over(d0).is_err(), "rien à reporter : pas de frais pour rien");
}

#[test]
fn anyone_can_open_todays_pool_so_the_first_publisher_pays_no_rent_for_it() {
    let mut ctx = ctx();
    let a = ctx.new_user();
    ctx.stake(&a, 100 * SKR).unwrap();
    let today = ctx.today();
    ctx.open_day_pool().unwrap();
    assert_eq!(ctx.day_pool_state(today).unwrap().day, today);
    ctx.open_day_pool().unwrap();

    let before = ctx.svm.get_account(&a.pubkey()).unwrap().lamports;
    ctx.check_in(&a).unwrap();
    let check_in_rent = ctx.svm.get_account(&ctx.check_in_address(&a.pubkey(), today)).unwrap().lamports;
    assert_eq!(before - ctx.svm.get_account(&a.pubkey()).unwrap().lamports, check_in_rent + 10_000, "rente du check-in et deux signatures");
}

#[test]
fn opening_another_days_pool_is_refused() {
    let mut ctx = ctx();
    let tomorrow = ctx.today() + 1;
    assert!(ctx.open_day_pool_on(tomorrow).is_err());
}
