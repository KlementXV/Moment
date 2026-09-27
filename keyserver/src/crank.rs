use crate::{
    chain::{self, Config, DayPool, Profile, RpcChain, DAY},
    error::Result,
    protocol::{self, Key},
};
use async_trait::async_trait;
use ed25519_dalek::{Signer, SigningKey};
use std::sync::Arc;

const MARGIN: i64 = 300;
const RETRY: i64 = 900;

#[async_trait]
pub trait CrankChain: Send + Sync {
    async fn config(&self) -> Result<Config>;
    async fn profiles(&self) -> Result<Vec<Profile>>;
    async fn latest_blockhash(&self) -> Result<Key>;
    async fn send_transaction(&self, raw: &[u8]) -> Result<()>;
    async fn check_ins(&self) -> Result<Vec<(Key, i64)>> {
        Ok(vec![])
    }
    async fn day_pools(&self) -> Result<Vec<DayPool>> {
        Ok(vec![])
    }
}

pub fn next_run(now: i64, close_delay: i64) -> i64 {
    let today = now.div_euclid(DAY);
    [today, today + 1]
        .iter()
        .flat_map(|day| {
            let start = day * DAY + MARGIN;
            let last = day * DAY + close_delay + MARGIN;
            (0..)
                .map(move |k| start + k * RETRY)
                .take_while(move |at| *at < last)
                .chain(std::iter::once(last))
        })
        .filter(|at| *at > now)
        .min()
        .expect("tomorrow is always ahead")
}

type Ix = (Key, Vec<(Key, bool)>, Vec<u8>);

fn signed_message(payer: &SigningKey, instructions: &[Ix], blockhash: &Key) -> Vec<u8> {
    let payer_key = payer.verifying_key().to_bytes();
    let all = || instructions.iter().flat_map(|(_, accounts, _)| accounts.iter());
    let mut keys = vec![payer_key];
    for (key, _) in all().filter(|(_, writable)| *writable) {
        if !keys.contains(key) {
            keys.push(*key);
        }
    }
    let writable = keys.len();
    let readonly = all().map(|(key, _)| *key).chain(instructions.iter().map(|(program, _, _)| *program));
    for key in readonly {
        if !keys.contains(&key) {
            keys.push(key);
        }
    }
    let index = |key: &Key| keys.iter().position(|k| k == key).expect("key listed") as u8;
    let mut message = vec![1, 0, (keys.len() - writable) as u8, keys.len() as u8];
    keys.iter().for_each(|k| message.extend_from_slice(k));
    message.extend_from_slice(blockhash);
    message.push(instructions.len() as u8);
    for (program, accounts, data) in instructions {
        message.extend_from_slice(&[index(program), accounts.len() as u8]);
        message.extend(accounts.iter().map(|(key, _)| index(key)));
        message.push(data.len() as u8);
        message.extend_from_slice(data);
    }
    let mut raw = vec![1];
    raw.extend_from_slice(&payer.sign(&message).to_bytes());
    raw.extend_from_slice(&message);
    raw
}

fn instruction_data(name: &str, day: i64) -> Vec<u8> {
    let mut data = chain::discriminator(&format!("global:{name}")).to_vec();
    data.extend_from_slice(&day.to_le_bytes());
    data
}

pub fn reap_transaction(
    program: &Key,
    caller: &SigningKey,
    owner: &Key,
    day: i64,
    pools: &[(i64, bool)],
    blockhash: &Key,
) -> Vec<u8> {
    let pool = |day: i64| chain::pda(program, &[b"day_pool", &day.to_le_bytes()]).0;
    let mut accounts = vec![
        (caller.verifying_key().to_bytes(), true),
        (*owner, false),
        (chain::pda(program, &[b"config"]).0, false),
        (chain::pda(program, &[b"profile", owner]).0, true),
        (pool(day), true),
        ([0; 32], false),
    ];
    accounts.extend(pools.iter().map(|(d, writable)| (pool(*d), *writable)));
    signed_message(caller, &[(*program, accounts, instruction_data("reap", day))], blockhash)
}

pub fn open_and_roll_over_transaction(
    program: &Key,
    caller: &SigningKey,
    today: i64,
    open: bool,
    from_days: &[i64],
    blockhash: &Key,
) -> Vec<u8> {
    let payer = caller.verifying_key().to_bytes();
    let pool = |day: i64| chain::pda(program, &[b"day_pool", &day.to_le_bytes()]).0;
    let config = chain::pda(program, &[b"config"]).0;
    let mut instructions: Vec<Ix> = Vec::new();
    if open {
        instructions.push((
            *program,
            vec![(payer, true), (pool(today), true), ([0; 32], false)],
            instruction_data("open_day_pool", today),
        ));
    }
    for from_day in from_days {
        let mut data = instruction_data("roll_over_day_pool", today);
        data.extend_from_slice(&from_day.to_le_bytes());
        instructions.push((
            *program,
            vec![
                (payer, true),
                (config, false),
                (pool(*from_day), true),
                (pool(today), true),
                ([0; 32], false),
            ],
            data,
        ));
    }
    signed_message(caller, &instructions, blockhash)
}

const CLOSES_PER_TRANSACTION: usize = 8;

pub fn close_check_ins_transaction(
    program: &Key,
    caller: &SigningKey,
    items: &[(Key, i64)],
    blockhash: &Key,
) -> Vec<u8> {
    let payer = caller.verifying_key().to_bytes();
    let instructions: Vec<Ix> = items
        .iter()
        .map(|(owner, day)| {
            let check_in = chain::pda(program, &[b"checkin", owner, &day.to_le_bytes()]).0;
            (
                *program,
                vec![(payer, true), (*owner, true), (check_in, true)],
                instruction_data("close_check_in", *day),
            )
        })
        .collect();
    signed_message(caller, &instructions, blockhash)
}

#[derive(Debug, Default)]
pub struct CrankReport {
    pub sent: usize,
    pub failed: usize,
    pub closed: usize,
    pub opened: bool,
    pub rolled_over: usize,
}

pub async fn crank_once(chain: &dyn CrankChain, program: &Key, signer: &SigningKey, now: i64) -> Result<CrankReport> {
    let config = chain.config().await?;
    let today = now.div_euclid(DAY);
    let payer = signer.verifying_key().to_bytes();
    let mut report = CrankReport::default();
    let mut blockhash = chain.latest_blockhash().await?;
    maintain_pools(chain, program, signer, &config, now, blockhash, &mut report).await;
    for profile in chain.profiles().await? {
        if profile.owner == payer || !profile.needs_reap(&config, now) {
            continue;
        }
        let sent = report.sent + report.failed;
        if sent > 0 && sent % 100 == 0 {
            if let Ok(fresh) = chain.latest_blockhash().await {
                blockhash = fresh;
            }
        }
        let pools = profile.settlement_pools(profile.settle_bound(today));
        let raw = reap_transaction(program, signer, &profile.owner, today, &pools, &blockhash);
        match chain.send_transaction(&raw).await {
            Ok(()) => report.sent += 1,
            Err(_) => {
                report.failed += 1;
                tracing::warn!(owner = %protocol::address_string(&profile.owner), "crank reap: send failed");
            }
        }
    }
    close_old_check_ins(chain, program, signer, today, blockhash, &mut report).await;
    Ok(report)
}

const ROLL_OVERS_PER_TRANSACTION: usize = 8;

async fn maintain_pools(
    chain: &dyn CrankChain,
    program: &Key,
    signer: &SigningKey,
    config: &Config,
    now: i64,
    blockhash: Key,
    report: &mut CrankReport,
) {
    let Ok(pools) = chain.day_pools().await else {
        tracing::warn!("crank pools: day pools unavailable");
        return;
    };
    let today = now.div_euclid(DAY);
    let open = !pools.iter().any(|pool| pool.day == today);
    let orphans: Vec<i64> = pools
        .iter()
        .filter(|pool| {
            pool.day < today
                && pool.total_stake == 0
                && pool.penalties > 0
                && now >= chain::closes_at(pool.day, config.pool_close_delay)
        })
        .map(|pool| pool.day)
        .collect();
    let mut batches: Vec<&[i64]> = orphans.chunks(ROLL_OVERS_PER_TRANSACTION).collect();
    if batches.is_empty() && open {
        batches.push(&[]);
    }
    for (n, batch) in batches.into_iter().enumerate() {
        let open_now = open && n == 0;
        let raw = open_and_roll_over_transaction(program, signer, today, open_now, batch, &blockhash);
        match chain.send_transaction(&raw).await {
            Ok(()) => {
                report.opened |= open_now;
                report.rolled_over += batch.len();
            }
            Err(_) => {
                report.failed += 1;
                tracing::warn!(count = batch.len(), "crank pools: send failed");
            }
        }
    }
}

async fn close_old_check_ins(
    chain: &dyn CrankChain,
    program: &Key,
    signer: &SigningKey,
    today: i64,
    blockhash: Key,
    report: &mut CrankReport,
) {
    let Ok(check_ins) = chain.check_ins().await else {
        tracing::warn!("crank close: check-ins unavailable");
        return;
    };
    let old: Vec<(Key, i64)> = check_ins.into_iter().filter(|(_, day)| *day <= today - 2).collect();
    let blockhash = chain.latest_blockhash().await.unwrap_or(blockhash);
    for batch in old.chunks(CLOSES_PER_TRANSACTION) {
        let raw = close_check_ins_transaction(program, signer, batch, &blockhash);
        match chain.send_transaction(&raw).await {
            Ok(()) => report.closed += batch.len(),
            Err(_) => {
                report.failed += 1;
                tracing::warn!(count = batch.len(), "crank close: send failed");
            }
        }
    }
}

fn unix_now() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0)
}

pub async fn run(chain: Arc<RpcChain>, program: Key, signer: SigningKey) {
    loop {
        let delay = match CrankChain::config(chain.as_ref()).await {
            Ok(config) => config.pool_close_delay,
            Err(_) => 21_600,
        };
        let now = unix_now();
        let wait = (next_run(now, delay) - now).max(1) as u64;
        tokio::time::sleep(std::time::Duration::from_secs(wait)).await;
        match crank_once(chain.as_ref(), &program, &signer, unix_now()).await {
            Ok(report) => tracing::info!(
                sent = report.sent,
                closed = report.closed,
                opened = report.opened,
                rolled_over = report.rolled_over,
                failed = report.failed,
                "crank reap"
            ),
            Err(_) => tracing::warn!("crank reap: chain unavailable"),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::chain::{Config, DayPool, Profile};
    use std::sync::Mutex;

    #[test]
    fn retries_every_quarter_hour_until_the_previous_pool_closes() {
        let day = 100 * 86_400;
        assert_eq!(next_run(day, 21_600), day + 300);
        assert_eq!(next_run(day + 300, 21_600), day + 1_200);
        assert_eq!(next_run(day + 20_000, 21_600), day + 20_100);
        assert_eq!(next_run(day + 21_000, 21_600), day + 21_900);
        assert_eq!(next_run(day + 21_900, 21_600), day + 86_400 + 300);
        assert_eq!(next_run(day + 3_000, 3_600), day + 3_900);
        assert_eq!(next_run(day + 3_900, 3_600), day + 86_400 + 300);
    }

    #[test]
    fn reap_transaction_is_a_signed_legacy_message() {
        let program = [9; 32];
        let caller = SigningKey::from_bytes(&[3; 32]);
        let owner = [4; 32];
        let raw = reap_transaction(&program, &caller, &owner, 100, &[(98, false), (99, true)], &[6; 32]);
        assert_eq!(raw[0], 1);
        let message = &raw[65..];
        crate::protocol::verify(&caller.verifying_key().to_bytes(), message, &raw[1..65]).unwrap();
        assert_eq!(&message[..4], &[1, 0, 5, 9]);
        let key = |i: usize| &message[4 + 32 * i..4 + 32 * (i + 1)];
        assert_eq!(key(0), caller.verifying_key().as_bytes());
        assert_eq!(key(1), &crate::chain::pda(&program, &[b"profile", &owner]).0);
        assert_eq!(key(2), &crate::chain::pda(&program, &[b"day_pool", &100i64.to_le_bytes()]).0);
        assert_eq!(key(3), &crate::chain::pda(&program, &[b"day_pool", &99i64.to_le_bytes()]).0);
        assert_eq!(key(8), &program);
        let ix = &message[4 + 32 * 9 + 32..];
        assert_eq!(ix[0], 1, "une instruction");
        assert_eq!(ix[1], 8, "index du programme");
        assert_eq!(&ix[2..11], &[8, 0, 4, 5, 1, 2, 6, 7, 3]);
        assert_eq!(ix[11], 16);
        assert_eq!(&ix[12..20], &crate::chain::discriminator("global:reap"));
        assert_eq!(&ix[20..28], &100i64.to_le_bytes());
    }

    #[test]
    fn old_check_ins_close_in_one_signed_transaction() {
        let program = [9; 32];
        let caller = SigningKey::from_bytes(&[3; 32]);
        let items = [([4; 32], 98i64), ([5; 32], 97i64)];
        let raw = close_check_ins_transaction(&program, &caller, &items, &[6; 32]);
        let message = &raw[65..];
        crate::protocol::verify(&caller.verifying_key().to_bytes(), message, &raw[1..65]).unwrap();
        assert_eq!(&message[..4], &[1, 0, 1, 6]);
        let key = |i: usize| &message[4 + 32 * i..4 + 32 * (i + 1)];
        assert_eq!(key(1), &[4; 32]);
        assert_eq!(key(2), &crate::chain::pda(&program, &[b"checkin", &[4; 32], &98i64.to_le_bytes()]).0);
        assert_eq!(key(5), &program);
        let ix = &message[4 + 32 * 6 + 32..];
        assert_eq!(ix[0], 2, "une instruction par check-in");
        assert_eq!(&ix[1..5], &[5, 3, 0, 1], "programme, 3 comptes : caller, owner, check_in");
        assert_eq!(ix[5], 2);
        assert_eq!(ix[6], 16);
        assert_eq!(&ix[7..15], &crate::chain::discriminator("global:close_check_in"));
        assert_eq!(&ix[15..23], &98i64.to_le_bytes());
    }

    #[test]
    fn opening_and_rolling_over_pools_are_signed_transactions() {
        let program = [9; 32];
        let caller = SigningKey::from_bytes(&[3; 32]);
        let raw = open_and_roll_over_transaction(&program, &caller, 101, true, &[98, 99], &[6; 32]);
        let message = &raw[65..];
        crate::protocol::verify(&caller.verifying_key().to_bytes(), message, &raw[1..65]).unwrap();
        let pool = |d: i64| crate::chain::pda(&program, &[b"day_pool", &d.to_le_bytes()]).0;
        assert_eq!(&message[..4], &[1, 0, 3, 7]);
        let key = |i: usize| &message[4 + 32 * i..4 + 32 * (i + 1)];
        assert_eq!(key(1), &pool(101));
        assert_eq!(key(2), &pool(98));
        assert_eq!(key(3), &pool(99));
        let ix = &message[4 + 32 * 7 + 32..];
        assert_eq!(ix[0], 3, "open_day_pool puis deux reports");
        assert_eq!(&ix[1..6], &[6, 3, 0, 1, 4]);
        assert_eq!(ix[6], 16);
        assert_eq!(&ix[7..15], &crate::chain::discriminator("global:open_day_pool"));
        let second = &ix[23..];
        assert_eq!(&second[..7], &[6, 5, 0, 5, 2, 1, 4]);
        assert_eq!(second[7], 24);
        assert_eq!(&second[8..16], &crate::chain::discriminator("global:roll_over_day_pool"));
        assert_eq!(&second[16..24], &101i64.to_le_bytes());
        assert_eq!(&second[24..32], &98i64.to_le_bytes());
    }

    struct Fake { profiles: Vec<Profile>, check_ins: Vec<(Key, i64)>, pools: Vec<DayPool>, sent: Mutex<Vec<Vec<u8>>> }
    #[async_trait::async_trait]
    impl CrankChain for Fake {
        async fn config(&self) -> Result<Config> {
            Ok(Config { authority: [0; 32], min_stake: 1, decay_bps: 1000, max_decay_days: 30, pool_close_delay: 21_600 })
        }
        async fn profiles(&self) -> Result<Vec<Profile>> { Ok(self.profiles.clone()) }
        async fn check_ins(&self) -> Result<Vec<(Key, i64)>> { Ok(self.check_ins.clone()) }
        async fn day_pools(&self) -> Result<Vec<DayPool>> { Ok(self.pools.clone()) }
        async fn latest_blockhash(&self) -> Result<Key> { Ok([6; 32]) }
        async fn send_transaction(&self, raw: &[u8]) -> Result<()> {
            self.sent.lock().unwrap().push(raw.to_vec());
            Ok(())
        }
    }

    struct FlakyBlockhash {
        late: usize,
        blockhash_calls: std::sync::atomic::AtomicUsize,
        sent: std::sync::atomic::AtomicUsize,
    }
    #[async_trait::async_trait]
    impl CrankChain for FlakyBlockhash {
        async fn config(&self) -> Result<Config> {
            Ok(Config { authority: [0; 32], min_stake: 1, decay_bps: 1000, max_decay_days: 30, pool_close_delay: 21_600 })
        }
        async fn profiles(&self) -> Result<Vec<Profile>> {
            Ok((0..self.late)
                .map(|i| Profile {
                    owner: [(i % 250) as u8 + 1, (i / 250) as u8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0],
                    staked: 100, settled_day: 99, exit_unlock_at: 0, active: true,
                    pending_days: [-1, -1], pending_stakes: [0, 0],
                })
                .collect())
        }
        async fn latest_blockhash(&self) -> Result<Key> {
            use std::sync::atomic::Ordering;
            if self.blockhash_calls.fetch_add(1, Ordering::SeqCst) == 0 {
                Ok([6; 32])
            } else {
                Err(crate::error::Error::unavailable())
            }
        }
        async fn send_transaction(&self, _raw: &[u8]) -> Result<()> {
            self.sent.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
            Ok(())
        }
    }

    #[tokio::test]
    async fn a_failed_blockhash_refresh_does_not_drop_the_remaining_profiles() {
        let chain = FlakyBlockhash {
            late: 250,
            blockhash_calls: Default::default(),
            sent: Default::default(),
        };
        let now = 101 * 86_400 + 300;
        let report = crank_once(&chain, &[9; 32], &SigningKey::from_bytes(&[3; 32]), now)
            .await
            .unwrap();
        assert_eq!((report.sent, report.failed), (250, 0));
    }

    #[tokio::test]
    async fn only_profiles_with_something_to_reap_get_a_transaction() {
        let profile = |owner: u8, settled_day: i64, pending: i64, active: bool| Profile {
            owner: [owner; 32], staked: 100, settled_day, exit_unlock_at: 0, active,
            pending_days: [pending, -1], pending_stakes: [100, 0],
        };
        let now = 101 * 86_400 + 300;
        let chain = Fake {
            profiles: vec![
                profile(1, 99, 99, true),
                profile(2, 100, 100, true),
                profile(3, 99, -1, false),
            ],
            check_ins: vec![],
            pools: vec![DayPool { day: 101, penalties: 0, total_stake: 0 }],
            sent: Mutex::new(vec![]),
        };
        let report = crank_once(&chain, &[9; 32], &SigningKey::from_bytes(&[3; 32]), now).await.unwrap();
        assert_eq!((report.sent, report.failed), (1, 0));
    }

    #[tokio::test]
    async fn todays_pool_is_opened_and_orphan_closed_pools_roll_over() {
        let now = 101 * 86_400 + 7 * 3_600;
        let pool = |day, penalties, total_stake| DayPool { day, penalties, total_stake };
        let chain = Fake {
            profiles: vec![],
            check_ins: vec![],
            pools: vec![
                pool(99, 20, 0),
                pool(100, 5, 0),
                pool(98, 30, 300),
                pool(97, 0, 0),
            ],
            sent: Mutex::new(vec![]),
        };
        let report = crank_once(&chain, &[9; 32], &SigningKey::from_bytes(&[3; 32]), now).await.unwrap();
        assert!(report.opened, "pool du jour absent : créé par le crank");
        assert_eq!(report.rolled_over, 2);
        assert_eq!(chain.sent.lock().unwrap().len(), 1, "une seule transaction");
    }

    #[tokio::test]
    async fn a_pool_still_open_is_not_rolled_over() {
        let now = 101 * 86_400 + 3 * 3_600;
        let chain = Fake {
            profiles: vec![],
            check_ins: vec![],
            pools: vec![DayPool { day: 101, penalties: 0, total_stake: 0 }, DayPool { day: 100, penalties: 5, total_stake: 0 }],
            sent: Mutex::new(vec![]),
        };
        let report = crank_once(&chain, &[9; 32], &SigningKey::from_bytes(&[3; 32]), now).await.unwrap();
        assert!(!report.opened);
        assert_eq!(report.rolled_over, 0);
        assert!(chain.sent.lock().unwrap().is_empty());
    }

    #[tokio::test]
    async fn check_ins_from_two_days_ago_are_closed_in_batches() {
        let now = 101 * 86_400 + 300;
        let mut check_ins: Vec<(Key, i64)> = (0..10u8).map(|i| ([i + 1; 32], 99)).collect();
        check_ins.push(([50; 32], 100));
        check_ins.push(([51; 32], 101));
        let pools = vec![DayPool { day: 101, penalties: 0, total_stake: 0 }];
        let chain = Fake { profiles: vec![], check_ins, pools, sent: Mutex::new(vec![]) };
        let report = crank_once(&chain, &[9; 32], &SigningKey::from_bytes(&[3; 32]), now).await.unwrap();
        assert_eq!(report.closed, 10);
        assert_eq!(chain.sent.lock().unwrap().len(), 2, "8 fermetures puis 2");
    }
}
