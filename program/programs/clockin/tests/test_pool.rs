mod common;

use anchor_lang::solana_program::instruction::AccountMeta;
use clockin::economy::share;
use common::{Ctx, User, FAUCET_AMOUNT, SKR};

const HOUR: i64 = 3_600;

/// Paramètres de la spec : 10 % par jour manqué.
fn ctx() -> Ctx {
    let mut ctx = Ctx::empty();
    ctx.initialize_config_with(|params| params.decay_bps = 1000).unwrap();
    ctx
}

/// A, B et C publient le jour D0 ; seuls A et B publient D1. Renvoie D1.
fn three_members_one_absent(ctx: &mut Ctx) -> (User, User, User, i64) {
    let (a, b, c) = (ctx.new_user(), ctx.new_user(), ctx.new_user());
    for user in [&a, &b, &c] {
        ctx.stake(user, 100 * SKR).unwrap();
        ctx.check_in(user).unwrap();
    }
    ctx.warp_days(1);
    let d1 = ctx.today();
    ctx.check_in(&a).unwrap();
    ctx.check_in(&b).unwrap();
    (a, b, c, d1)
}

#[test]
fn an_absent_member_pays_the_publishers_of_the_day_pro_rata() {
    let mut ctx = ctx();
    let (a, b, c, d1) = three_members_one_absent(&mut ctx);

    ctx.warp_to_next(5 * 60); // D2 00:05 : le crank règle les absents
    ctx.reap(&c.pubkey()).unwrap();
    assert_eq!(ctx.profile_state(&c.profile).staked, 90 * SKR);
    let pool = ctx.day_pool_state(d1).unwrap();
    assert_eq!(pool.penalties, 10 * SKR);
    assert_eq!(pool.total_stake, 200 * SKR);
    assert_eq!(pool.winners_count, 2);

    assert!(ctx.reap(&a.pubkey()).is_err(), "le pool de la veille n'est pas clôturé");

    ctx.warp_to_next(6 * HOUR + 5 * 60); // D2 06:05 : après la clôture
    ctx.reap(&a.pubkey()).unwrap();
    ctx.reap(&b.pubkey()).unwrap();
    assert_eq!(ctx.profile_state(&a.profile).staked, 105 * SKR);
    assert_eq!(ctx.profile_state(&b.profile).staked, 105 * SKR);
    assert!(ctx.reap(&a.pubkey()).is_err(), "une créance ne s'encaisse qu'une fois");
    assert_eq!(ctx.profile_state(&a.profile).staked, 105 * SKR);
    assert_eq!(ctx.vault_balance(), 300 * SKR, "aucun token créé ni perdu");
}

#[test]
fn a_penalty_settled_after_the_closure_feeds_the_current_pool() {
    let mut ctx = ctx();
    let (_a, _b, c, d1) = three_members_one_absent(&mut ctx);

    ctx.warp_to_next(7 * HOUR); // D2 07:00 : le pool de D1 est clôturé
    ctx.reap(&c.pubkey()).unwrap();

    assert_eq!(ctx.day_pool_state(d1).unwrap().penalties, 0);
    assert_eq!(ctx.day_pool_state(ctx.today()).unwrap().penalties, 10 * SKR);
}

#[test]
fn publishing_before_the_previous_pool_closes_keeps_two_claims() {
    let mut ctx = ctx();
    let (a, c) = (ctx.new_user(), ctx.new_user());
    ctx.stake(&a, 100 * SKR).unwrap();
    ctx.stake(&c, 100 * SKR).unwrap();
    let d0 = ctx.today();
    ctx.check_in(&a).unwrap(); // C manque D0

    ctx.warp_to_next(3 * HOUR); // D1 03:00
    ctx.reap(&c.pubkey()).unwrap();
    assert_eq!(ctx.day_pool_state(d0).unwrap().penalties, 10 * SKR);
    ctx.check_in(&a).unwrap();
    let profile = ctx.profile_state(&a.profile);
    assert_eq!(profile.pending_days, [d0, d0 + 1]);
    assert_eq!(profile.pending_stakes, [100 * SKR, 100 * SKR]);
    assert_eq!(profile.staked, 100 * SKR, "rien d'encaissé avant la clôture");

    ctx.warp_to_next(12 * HOUR); // D1 12:00 : D0 clôturé, D1 encore ouvert
    ctx.reap(&a.pubkey()).unwrap();
    let profile = ctx.profile_state(&a.profile);
    assert_eq!(profile.staked, 110 * SKR);
    assert_eq!(profile.pending_days, [-1, d0 + 1]);
}

#[test]
fn finalizing_while_a_claim_is_still_open_is_refused() {
    let mut ctx = ctx();
    ctx.update_config(|params| params.withdrawal_delay_seconds = 13 * HOUR).unwrap();
    let a = ctx.new_user();
    ctx.stake(&a, 100 * SKR).unwrap();
    ctx.check_in(&a).unwrap();
    ctx.request_exit(&a).unwrap(); // déblocage à D1 01:00

    ctx.warp_to_next(2 * HOUR);
    assert!(
        ctx.finalize_exit(&a.pubkey(), &a.token_account).is_err(),
        "la part de D0 n'est pas encore réclamable"
    );

    ctx.warp_to_next(6 * HOUR); // D1 06:00 : clôture de D0
    ctx.finalize_exit(&a.pubkey(), &a.token_account).unwrap();
    assert_eq!(ctx.token_balance(&a.token_account), FAUCET_AMOUNT);
    assert!(!ctx.profile_state(&a.profile).active);
}

#[test]
fn the_seed_is_shared_by_the_publishers_of_the_day() {
    let mut ctx = ctx();
    let a = ctx.new_user();
    ctx.stake(&a, 100 * SKR).unwrap();
    ctx.seed_pool(20 * SKR).unwrap();
    ctx.check_in(&a).unwrap();

    ctx.warp_to_next(6 * HOUR + 60);
    ctx.reap(&a.pubkey()).unwrap();
    assert_eq!(ctx.profile_state(&a.profile).staked, 120 * SKR);
}

#[test]
fn omitting_a_claimable_pool_is_refused_rather_than_skipped() {
    let mut ctx = ctx();
    let (a, _b, c, _d1) = three_members_one_absent(&mut ctx);
    ctx.warp_to_next(5 * 60);
    ctx.reap(&c.pubkey()).unwrap();
    ctx.warp_to_next(6 * HOUR + 5 * 60);

    let today = ctx.today();
    assert!(ctx.reap_with(&a.pubkey(), today, vec![]).is_err());
    assert_eq!(ctx.profile_state(&a.profile).staked, 100 * SKR);
    assert_ne!(ctx.profile_state(&a.profile).pending_days, [-1, -1]);
}

#[test]
fn a_read_only_penalty_pool_is_refused() {
    let mut ctx = ctx();
    let (_a, _b, c, d1) = three_members_one_absent(&mut ctx);
    ctx.warp_to_next(5 * 60);

    let penalty_pool = ctx.day_pool_address(d1);
    let pools: Vec<AccountMeta> = ctx
        .honest_pools(&c.pubkey())
        .into_iter()
        .map(|meta| if meta.pubkey == penalty_pool { AccountMeta::new_readonly(meta.pubkey, false) } else { meta })
        .collect();
    let today = ctx.today();
    assert!(ctx.reap_with(&c.pubkey(), today, pools).is_err());
    assert_eq!(ctx.day_pool_state(d1).unwrap().penalties, 0);
}

#[test]
fn a_stale_day_argument_is_refused() {
    let mut ctx = ctx();
    let (_a, _b, c, _d1) = three_members_one_absent(&mut ctx);
    ctx.warp_to_next(5 * 60);
    let pools = ctx.honest_pools(&c.pubkey());
    let yesterday = ctx.today() - 1;
    assert!(ctx.reap_with(&c.pubkey(), yesterday, pools).is_err());
}

#[test]
fn a_day_without_publishers_pays_nobody() {
    // Limite connue : l'amorçage d'un jour sans publieur reste dans le vault.
    let mut ctx = ctx();
    let a = ctx.new_user();
    ctx.stake(&a, 100 * SKR).unwrap();
    ctx.seed_pool(20 * SKR).unwrap(); // personne ne publie D0

    ctx.warp_days(1);
    ctx.check_in(&a).unwrap(); // A paie D0 : 10 SKR vers le pool de D1
    ctx.warp_days(1);
    ctx.check_in(&a).unwrap(); // encaisse sa part de D1

    let profile = ctx.profile_state(&a.profile);
    assert_eq!(profile.staked, 100 * SKR, "90 SKR + sa propre pénalité, rien de l'amorçage");
    assert_eq!(ctx.vault_balance(), 120 * SKR);
    let owed = profile.staked
        + (0..2)
            .filter(|i| profile.pending_days[*i] >= 0)
            .map(|i| {
                let pool = ctx.day_pool_state(profile.pending_days[i]).unwrap();
                share(pool.penalties, profile.pending_stakes[i], pool.total_stake)
            })
            .sum::<u64>();
    assert!(ctx.vault_balance() >= owed);
}

#[test]
fn a_claim_that_closes_while_the_transaction_is_in_flight_still_settles() {
    // Comptes choisis à 05:59:50, transaction exécutée à 06:00:10 : la créance
    // de la veille est devenue réclamable entre-temps.
    let mut ctx = ctx();
    let (a, _b, c, d1) = three_members_one_absent(&mut ctx);
    ctx.warp_to_next(5 * 60);
    ctx.reap(&c.pubkey()).unwrap();

    ctx.warp_to_next(6 * HOUR - 10);
    let pools = ctx.honest_pools(&a.pubkey());
    ctx.warp_seconds(20);
    let today = ctx.today();
    ctx.reap_with(&a.pubkey(), today, pools).unwrap();
    assert_eq!(ctx.profile_state(&a.profile).staked, 105 * SKR);
    assert!(ctx.profile_state(&a.profile).pending_days.iter().all(|d| *d != d1));
}

#[test]
fn a_client_clock_ahead_of_the_chain_still_routes_the_penalty() {
    // L'appareil avance : il croit le pool d'hier clôturé, la chaîne non.
    let mut ctx = ctx();
    let (_a, _b, c, d1) = three_members_one_absent(&mut ctx);
    ctx.warp_to_next(6 * HOUR + 10);
    let pools = ctx.honest_pools(&c.pubkey());
    ctx.warp_seconds(-20);
    let today = ctx.today();
    ctx.reap_with(&c.pubkey(), today, pools).unwrap();
    assert_eq!(ctx.day_pool_state(d1).unwrap().penalties, 10 * SKR);
}

#[test]
fn settling_pools_costs_little_compute() {
    // Les pools créés s'authentifient par leur bump stocké, sans recherche
    // d'adresse par essais (find_program_address). `reap` n'utilise que des bumps
    // stockés : sa consommation ne dépend pas des clés aléatoires du test.
    // Build opt-level "z" ; l'ancienne recherche par find_program_address
    // dépassait ces seuils de loin.
    let mut ctx = ctx();
    let (a, _b, c, _d1) = three_members_one_absent(&mut ctx);
    ctx.warp_to_next(5 * 60);
    let penalty = ctx.reap(&c.pubkey()).unwrap().compute_units_consumed;
    ctx.warp_to_next(6 * HOUR + 5 * 60);
    let claim = ctx.reap(&a.pubkey()).unwrap().compute_units_consumed;
    assert!(penalty <= 32_000, "reap pénalité + part : {penalty} CU");
    assert!(claim <= 22_000, "reap part seule : {claim} CU");
}

#[test]
fn a_program_owned_impostor_pool_is_refused() {
    // Un compte DayPool plausible (même jour, pénalités gonflées) mais hors de
    // l'adresse PDA : il ne doit jamais servir à payer une part.
    use anchor_lang::AccountSerialize;
    let mut ctx = ctx();
    let a = ctx.new_user();
    ctx.stake(&a, 100 * SKR).unwrap();
    let d0 = ctx.today();
    ctx.check_in(&a).unwrap();
    ctx.warp_to_next(7 * HOUR);

    let genuine = ctx.day_pool_state(d0).unwrap();
    let mut forged = genuine.clone();
    forged.penalties = 1_000 * SKR;
    let mut data = Vec::new();
    forged.try_serialize(&mut data).unwrap();
    let impostor = anchor_lang::prelude::Pubkey::new_unique();
    ctx.svm
        .set_account(
            impostor,
            solana_account::Account {
                lamports: 1_000_000_000,
                data,
                owner: clockin::id(),
                executable: false,
                rent_epoch: 0,
            },
        )
        .unwrap();
    let today = ctx.today();
    let result = ctx.reap_with(&a.pubkey(), today, vec![AccountMeta::new_readonly(impostor, false)]);
    assert!(result.is_err());
    assert_eq!(ctx.profile_state(&a.profile).staked, 100 * SKR);
}
