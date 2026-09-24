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

#[test]
fn finalizing_before_the_unlock_is_refused() {
    let mut ctx = Ctx::new();
    let user = ctx.new_user();
    ctx.stake(&user, 40 * SKR).unwrap();
    ctx.request_exit(&user).unwrap();

    ctx.warp_seconds(172_800 - 60);
    let owner = user.pubkey();
    let token_account = user.token_account;
    assert!(ctx.finalize_exit(&owner, &token_account).is_err());
}

#[test]
fn the_spec_example_costs_exactly_one_missed_day() {
    // Check-in lundi, demande lundi, aucun autre check-in, déblocage mercredi
    // à la même heure : seul mardi est manqué (§6.3).
    let mut ctx = Ctx::new();
    let user = ctx.new_user();
    ctx.stake(&user, 40 * SKR).unwrap();
    ctx.check_in(&user).unwrap();
    ctx.request_exit(&user).unwrap();

    ctx.warp_days(2);
    let owner = user.pubkey();
    let token_account = user.token_account;
    ctx.finalize_exit(&owner, &token_account).unwrap();

    let profile = ctx.profile_state(&user.profile);
    assert_eq!(profile.staked, 0);
    assert!(!profile.active);
    assert_eq!(profile.streak, 0);
    assert_eq!(profile.exit_unlock_at, 0);
    // 60 SKR restants au wallet + 30 SKR récupérés (40 décimés une fois).
    assert_eq!(ctx.token_balance(&token_account), 90 * SKR);
    // Le vault n'est pas vide : il garde le pool, soit les 10 SKR que ce même
    // utilisateur vient de perdre et qui appartiennent désormais aux réguliers.
    assert_eq!(ctx.pooled(), 10 * SKR);
    assert_eq!(ctx.vault_balance(), ctx.pooled());
}

#[test]
fn checking_in_during_the_wait_costs_nothing_at_all() {
    let mut ctx = Ctx::new();
    let user = ctx.new_user();
    ctx.stake(&user, 40 * SKR).unwrap();
    ctx.check_in(&user).unwrap();
    ctx.request_exit(&user).unwrap();

    ctx.warp_days(1);
    ctx.check_in(&user).unwrap();
    ctx.warp_days(1);

    let owner = user.pubkey();
    let token_account = user.token_account;
    ctx.finalize_exit(&owner, &token_account).unwrap();

    assert_eq!(ctx.token_balance(&token_account), 100 * SKR, "sortie sans perte");
    assert_eq!(ctx.pooled(), 0);
}

#[test]
fn a_late_finalization_adds_no_extra_decay() {
    let mut ctx = Ctx::new();
    let user = ctx.new_user();
    ctx.stake(&user, 40 * SKR).unwrap();
    ctx.check_in(&user).unwrap();
    ctx.request_exit(&user).unwrap();

    ctx.warp_days(9); // sept jours après le déblocage
    let owner = user.pubkey();
    let token_account = user.token_account;
    ctx.finalize_exit(&owner, &token_account).unwrap();

    assert_eq!(ctx.token_balance(&token_account), 90 * SKR, "toujours un seul jour manqué");
}

#[test]
fn a_late_reap_adds_no_extra_decay_either() {
    let mut ctx = Ctx::new();
    let user = ctx.new_user();
    ctx.stake(&user, 40 * SKR).unwrap();
    ctx.check_in(&user).unwrap();
    ctx.request_exit(&user).unwrap();

    ctx.warp_days(9);
    let owner = user.pubkey();
    ctx.reap(&owner).unwrap();
    assert_eq!(ctx.profile_state(&user.profile).staked, 30 * SKR);
    assert!(ctx.reap(&owner).is_err(), "plus rien à régler après le déblocage");
}

#[test]
fn a_third_party_can_finalize_but_only_the_owner_is_paid() {
    let mut ctx = Ctx::new();
    let user = ctx.new_user();
    let keeper = ctx.new_user();
    ctx.stake(&user, 40 * SKR).unwrap();
    ctx.request_exit(&user).unwrap();
    ctx.warp_days(2);

    let owner = user.pubkey();
    let owner_token = user.token_account;
    let keeper_token = keeper.token_account;
    let keeper_before = ctx.token_balance(&keeper_token);

    ctx.finalize_exit_as(&keeper.keypair.insecure_clone(), &owner, &owner_token).unwrap();

    // Aucun check-in pendant l'attente : les jours J et J+1 sont manqués, donc
    // 40 SKR deviennent 22,5 SKR, versés au propriétaire et à personne d'autre.
    assert_eq!(ctx.token_balance(&owner_token), 82_500_000_000);
    assert_eq!(ctx.token_balance(&keeper_token), keeper_before, "le keeper ne touche rien");
}

#[test]
fn finalizing_into_someone_elses_token_account_is_refused() {
    let mut ctx = Ctx::new();
    let user = ctx.new_user();
    let thief = ctx.new_user();
    ctx.stake(&user, 40 * SKR).unwrap();
    ctx.request_exit(&user).unwrap();
    ctx.warp_days(2);

    let owner = user.pubkey();
    let thief_token = thief.token_account;
    assert!(ctx.finalize_exit(&owner, &thief_token).is_err());
}

#[test]
fn reopening_a_position_after_an_exit_starts_a_fresh_streak() {
    let mut ctx = Ctx::new();
    let user = ctx.new_user();
    ctx.stake(&user, 40 * SKR).unwrap();
    ctx.check_in(&user).unwrap();
    ctx.request_exit(&user).unwrap();
    ctx.warp_days(2);
    let owner = user.pubkey();
    let token_account = user.token_account;
    ctx.finalize_exit(&owner, &token_account).unwrap();

    ctx.stake(&user, 20 * SKR).unwrap();
    let profile = ctx.profile_state(&user.profile);
    assert!(profile.active);
    assert_eq!(profile.staked, 20 * SKR);
    assert_eq!(profile.streak, 0);
    assert_eq!(profile.settled_day, ctx.today() - 1);

    ctx.check_in(&user).unwrap();
    assert_eq!(ctx.profile_state(&user.profile).streak, 1);
}
