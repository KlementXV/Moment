mod common;

use common::{Ctx, DAY, SKR};

#[test]
fn request_exit_freezes_the_unlock_time() {
    let mut ctx = Ctx::new();
    let user = ctx.new_user();
    ctx.stake(&user, 40 * SKR).unwrap();

    let requested_at = ctx.now();
    ctx.request_exit(&user).unwrap();

    let profile = ctx.profile_state(&user.profile);
    assert_eq!(profile.exit_requested_at, requested_at);
    assert_eq!(profile.exit_unlock_at, requested_at + 172_800);
    assert!(profile.active, "la position reste active pendant l'attente");
}

#[test]
fn a_config_change_does_not_move_an_open_exit() {
    let mut ctx = Ctx::new();
    let user = ctx.new_user();
    ctx.stake(&user, 40 * SKR).unwrap();
    ctx.request_exit(&user).unwrap();
    let frozen = ctx.profile_state(&user.profile).exit_unlock_at;

    ctx.update_config(|params| params.withdrawal_delay_seconds = 10 * DAY).unwrap();

    assert_eq!(ctx.profile_state(&user.profile).exit_unlock_at, frozen);
}

#[test]
fn a_second_request_is_refused() {
    let mut ctx = Ctx::new();
    let user = ctx.new_user();
    ctx.stake(&user, 40 * SKR).unwrap();
    ctx.request_exit(&user).unwrap();

    assert!(ctx.request_exit(&user).is_err());
}

#[test]
fn check_in_is_possible_while_waiting_and_avoids_the_decay() {
    let mut ctx = Ctx::new();
    let user = ctx.new_user();
    ctx.stake(&user, 40 * SKR).unwrap();
    ctx.check_in(&user).unwrap();
    ctx.request_exit(&user).unwrap();

    ctx.warp_days(1);
    ctx.check_in(&user).unwrap();

    assert_eq!(ctx.profile_state(&user.profile).staked, 40 * SKR, "aucun jour manqué");
}

#[test]
fn check_in_after_the_unlock_is_refused() {
    let mut ctx = Ctx::new();
    let user = ctx.new_user();
    ctx.stake(&user, 40 * SKR).unwrap();
    ctx.request_exit(&user).unwrap();

    ctx.warp_days(2);
    assert!(ctx.check_in(&user).is_err(), "la position n'est plus exposée");
}

#[test]
fn cancelling_before_the_unlock_reopens_the_position() {
    let mut ctx = Ctx::new();
    let user = ctx.new_user();
    ctx.stake(&user, 40 * SKR).unwrap();
    ctx.request_exit(&user).unwrap();

    ctx.warp_seconds(3_600);
    ctx.cancel_exit(&user).unwrap();

    let profile = ctx.profile_state(&user.profile);
    assert_eq!(profile.exit_unlock_at, 0);
    assert_eq!(profile.exit_requested_at, 0);
    assert!(profile.active);

    // Une nouvelle demande repart de zéro.
    let now = ctx.now();
    ctx.request_exit(&user).unwrap();
    assert_eq!(ctx.profile_state(&user.profile).exit_unlock_at, now + 172_800);
}

#[test]
fn cancelling_after_the_unlock_is_refused() {
    let mut ctx = Ctx::new();
    let user = ctx.new_user();
    ctx.stake(&user, 40 * SKR).unwrap();
    ctx.request_exit(&user).unwrap();

    ctx.warp_days(2);
    assert!(ctx.cancel_exit(&user).is_err());
}

#[test]
fn cancelling_never_gives_back_what_the_decay_already_took() {
    let mut ctx = Ctx::new();
    let user = ctx.new_user();
    ctx.stake(&user, 40 * SKR).unwrap();
    ctx.check_in(&user).unwrap();

    ctx.warp_days(2); // un jour entier manqué
    ctx.request_exit(&user).unwrap();
    let after_request = ctx.profile_state(&user.profile).staked;
    assert_eq!(after_request, 30 * SKR, "request_exit règle d'abord les jours manqués");

    ctx.cancel_exit(&user).unwrap();
    assert_eq!(ctx.profile_state(&user.profile).staked, 30 * SKR);
}

#[test]
fn staking_during_an_exit_is_refused() {
    let mut ctx = Ctx::new();
    let user = ctx.new_user();
    ctx.stake(&user, 40 * SKR).unwrap();
    ctx.request_exit(&user).unwrap();

    assert!(ctx.stake(&user, 5 * SKR).is_err());
}
