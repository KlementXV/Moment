mod common;

use common::{Ctx, SKR};
use solana_keypair::Keypair;
use solana_signer::Signer;

#[test]
fn a_check_in_records_a_claim_instead_of_paying_a_reward() {
    let mut ctx = Ctx::new();
    ctx.seed_pool(100 * SKR).unwrap();
    let user = ctx.new_user();
    ctx.stake(&user, 50 * SKR).unwrap();

    ctx.check_in(&user).unwrap();

    let today = ctx.today();
    let profile = ctx.profile_state(&user.profile);
    assert_eq!(profile.staked, 50 * SKR);
    assert_eq!(profile.pending_days[0], today);
    assert_eq!(profile.pending_stakes[0], 50 * SKR);
    assert_eq!(profile.streak, 1);
    assert_eq!(profile.total_checkins, 1);
    assert_eq!(profile.last_checkin_day, today);
    assert_eq!(profile.settled_day, today);
    let pool = ctx.day_pool_state(today).unwrap();
    assert_eq!(pool.total_stake, 50 * SKR);
    assert_eq!(pool.winners_count, 1);
    assert_eq!(pool.penalties, 100 * SKR);

    let check_in = ctx.check_in_state(&ctx.check_in_address(&user.pubkey(), ctx.today()));
    assert_eq!(check_in.owner, user.pubkey());
    assert_eq!(check_in.day, ctx.today());
    assert_eq!(check_in.commitment, [7u8; 32]);
    assert_eq!(check_in.blob_ref, [9u8; 32]);
    assert_eq!(check_in.streak_at_checkin, 1);
}

#[test]
fn a_second_check_in_the_same_day_is_structurally_impossible() {
    let mut ctx = Ctx::new();
    let user = ctx.new_user();
    ctx.stake(&user, 50 * SKR).unwrap();

    ctx.check_in(&user).unwrap();
    assert!(ctx.check_in(&user).is_err(), "le PDA du jour existe déjà");
}

#[test]
fn check_in_without_the_publication_authority_is_refused() {
    let mut ctx = Ctx::new();
    let user = ctx.new_user();
    ctx.stake(&user, 50 * SKR).unwrap();

    let impostor = Keypair::new();
    ctx.svm.airdrop(&impostor.pubkey(), 1_000_000_000).unwrap();

    assert!(
        ctx.check_in_signed_by(&user, &impostor).is_err(),
        "une autorisation de publication forgée doit échouer"
    );
}

#[test]
fn check_in_below_the_minimum_stake_is_refused() {
    let mut ctx = Ctx::new();
    let user = ctx.new_user();
    ctx.stake(&user, 11 * SKR).unwrap();

    ctx.warp_days(3);
    assert!(ctx.check_in(&user).is_err(), "il faut recharger pour rejouer");
}

#[test]
fn a_day_that_is_not_today_is_refused() {
    let mut ctx = Ctx::new();
    let user = ctx.new_user();
    ctx.stake(&user, 50 * SKR).unwrap();

    let yesterday = ctx.today() - 1;
    assert!(
        ctx.check_in_on_day(&user, yesterday).is_err(),
        "le jour est décidé par l'horloge du réseau, pas par le client"
    );
}

#[test]
fn check_in_without_an_open_position_is_refused() {
    let mut ctx = Ctx::new();
    let user = ctx.new_user();
    assert!(ctx.check_in(&user).is_err());
}
