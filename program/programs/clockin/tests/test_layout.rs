//! Octets de référence des comptes, relus par le keyserver et l'app : si l'un des
//! trois décodeurs diverge de la disposition Borsh, son test tombe.
use anchor_lang::{prelude::Pubkey, AccountSerialize};
use clockin::state::{Config, DayPool, Profile};

const FIXTURE: &str = concat!(
    env!("CARGO_MANIFEST_DIR"),
    "/../../../keyserver/tests/fixtures/account-layouts-v2.hex"
);
const SKR: u64 = 1_000_000_000;

fn hex(account: &impl AccountSerialize) -> String {
    let mut bytes = Vec::new();
    account.try_serialize(&mut bytes).unwrap();
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

#[test]
fn account_layouts_match_the_shared_fixture() {
    let config = Config {
        admin: Pubkey::new_from_array([1; 32]),
        publication_authority: Pubkey::new_from_array([2; 32]),
        skr_mint: Pubkey::new_from_array([3; 32]),
        vault: Pubkey::new_from_array([4; 32]),
        min_stake: 500 * SKR,
        faucet_amount: 1_000 * SKR,
        withdrawal_delay_seconds: 172_800,
        pool_close_delay_seconds: 21_600,
        decay_bps: 1000,
        max_decay_days: 30,
        faucet_enabled: true,
        bump: 254,
        vault_bump: 253,
    };
    let profile = Profile {
        owner: Pubkey::new_from_array([5; 32]),
        staked: 123 * SKR,
        settled_day: 20_718,
        last_checkin_day: 20_718,
        exit_requested_at: 0,
        exit_unlock_at: 0,
        total_checkins: 7,
        streak: 3,
        active: true,
        faucet_claimed: true,
        bump: 252,
        pending_days: [20_717, 20_718],
        pending_stakes: [100 * SKR, 110 * SKR],
    };
    let pool = DayPool { day: 20_718, penalties: 30 * SKR, total_stake: 600 * SKR, winners_count: 6, bump: 251 };
    let actual = format!("config={}\nprofile={}\nday_pool={}\n", hex(&config), hex(&profile), hex(&pool));
    if std::env::var("UPDATE_LAYOUTS").is_ok() {
        std::fs::write(FIXTURE, &actual).unwrap();
    }
    assert_eq!(std::fs::read_to_string(FIXTURE).unwrap(), actual);
}
