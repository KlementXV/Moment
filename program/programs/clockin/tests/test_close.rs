mod common;

use common::{Ctx, SKR};
use solana_signer::Signer;

fn published(ctx: &mut Ctx) -> (common::User, i64) {
    let user = ctx.new_user();
    ctx.stake(&user, 50 * SKR).unwrap();
    ctx.check_in(&user).unwrap();
    let day = ctx.today();
    (user, day)
}

#[test]
fn an_old_check_in_is_closed_and_its_rent_returns_to_the_owner() {
    let mut ctx = Ctx::new();
    let (user, day) = published(&mut ctx);
    let address = ctx.check_in_address(&user.pubkey(), day);
    let rent = ctx.svm.get_account(&address).unwrap().lamports;
    let before = ctx.svm.get_account(&user.pubkey()).unwrap().lamports;
    let caller_before = ctx.svm.get_account(&ctx.admin.pubkey()).unwrap().lamports;

    ctx.warp_days(2);
    ctx.close_check_in(&user.pubkey(), day).unwrap();

    assert!(ctx.svm.get_account(&address).is_none_or(|a| a.lamports == 0));
    assert_eq!(ctx.svm.get_account(&user.pubkey()).unwrap().lamports, before + rent);
    assert!(
        ctx.svm.get_account(&ctx.admin.pubkey()).unwrap().lamports < caller_before,
        "l'appelant paie les frais et ne touche pas la rente"
    );
}

#[test]
fn today_and_yesterday_stay_open() {
    // Le keyserver relit le check-in du jour (feed) et celui de la veille
    // (confirmation d'une publication à cheval sur minuit).
    let mut ctx = Ctx::new();
    let (user, day) = published(&mut ctx);
    assert!(ctx.close_check_in(&user.pubkey(), day).is_err());
    ctx.warp_days(1);
    assert!(ctx.close_check_in(&user.pubkey(), day).is_err());
    assert!(ctx.svm.get_account(&ctx.check_in_address(&user.pubkey(), day)).is_some());
}

#[test]
fn the_rent_cannot_be_redirected_to_someone_else() {
    let mut ctx = Ctx::new();
    let (user, day) = published(&mut ctx);
    let thief = ctx.new_wallet();
    ctx.warp_days(2);
    assert!(ctx.close_check_in_to(&user.pubkey(), &thief.pubkey(), day).is_err());
}
