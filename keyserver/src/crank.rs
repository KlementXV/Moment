//! Daily crank: settles late profiles and pays out closed pools.
//! Permissionless on-chain; this server only pays the fees.
use crate::{
    chain::{self, Config, Profile, RpcChain, DAY},
    error::Result,
    protocol::{self, Key},
};
use async_trait::async_trait;
use ed25519_dalek::{Signer, SigningKey};
use std::sync::Arc;

/// First pass five minutes after midnight (late profiles), last pass five
/// minutes after the previous day's pool closes (payouts).
const MARGIN: i64 = 300;
/// Retry cadence in between: a failed or partial midnight pass must be retried
/// while yesterday's pool is still open, or its penalties go to today's pool.
const RETRY: i64 = 900;

#[async_trait]
pub trait CrankChain: Send + Sync {
    async fn config(&self) -> Result<Config>;
    async fn profiles(&self) -> Result<Vec<Profile>>;
    async fn latest_blockhash(&self) -> Result<Key>;
    async fn send_transaction(&self, raw: &[u8]) -> Result<()>;
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

pub fn reap_transaction(
    program: &Key,
    caller: &SigningKey,
    owner: &Key,
    day: i64,
    pools: &[(i64, bool)],
    blockhash: &Key,
) -> Vec<u8> {
    let payer = caller.verifying_key().to_bytes();
    let pool = |day: i64| chain::pda(program, &[b"day_pool", &day.to_le_bytes()]).0;
    let config = chain::pda(program, &[b"config"]).0;
    let profile = chain::pda(program, &[b"profile", owner]).0;
    let today_pool = pool(day);
    let mut writable = vec![profile, today_pool];
    writable.extend(pools.iter().filter(|(_, w)| *w).map(|(d, _)| pool(*d)));
    let mut readonly = vec![*owner, config, [0; 32]];
    readonly.extend(pools.iter().filter(|(_, w)| !*w).map(|(d, _)| pool(*d)));
    readonly.push(*program);
    let keys: Vec<Key> = std::iter::once(payer).chain(writable).chain(readonly.iter().copied()).collect();
    let index = |key: &Key| keys.iter().position(|k| k == key).expect("key listed") as u8;

    let mut accounts = vec![index(&payer), index(owner), index(&config), index(&profile), index(&today_pool), index(&[0; 32])];
    accounts.extend(pools.iter().map(|(d, _)| index(&pool(*d))));
    let mut data = chain::discriminator("global:reap").to_vec();
    data.extend_from_slice(&day.to_le_bytes());

    // Every length stays below 128: compact-u16 is a single byte.
    let mut message = vec![1, 0, readonly.len() as u8, keys.len() as u8];
    keys.iter().for_each(|k| message.extend_from_slice(k));
    message.extend_from_slice(blockhash);
    message.extend_from_slice(&[1, index(program), accounts.len() as u8]);
    message.extend_from_slice(&accounts);
    message.push(data.len() as u8);
    message.extend_from_slice(&data);
    let mut raw = vec![1];
    raw.extend_from_slice(&caller.sign(&message).to_bytes());
    raw.extend_from_slice(&message);
    raw
}

#[derive(Debug, Default)]
pub struct CrankReport {
    pub sent: usize,
    pub failed: usize,
}

/// Idempotent: a profile with nothing to reap is skipped, so the next pass
/// (at most `RETRY` later while yesterday's pool is open) picks up failures.
pub async fn crank_once(chain: &dyn CrankChain, program: &Key, signer: &SigningKey, now: i64) -> Result<CrankReport> {
    let config = chain.config().await?;
    let today = now.div_euclid(DAY);
    let payer = signer.verifying_key().to_bytes();
    let mut report = CrankReport::default();
    let mut blockhash = chain.latest_blockhash().await?;
    for profile in chain.profiles().await? {
        if profile.owner == payer || !profile.needs_reap(&config, now) {
            continue;
        }
        let sent = report.sent + report.failed;
        if sent > 0 && sent % 100 == 0 {
            // A blockhash stays valid for about a minute; a failed refresh keeps
            // the previous one rather than dropping the remaining profiles.
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
    Ok(report)
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
            Ok(report) => tracing::info!(sent = report.sent, failed = report.failed, "crank reap"),
            Err(_) => tracing::warn!("crank reap: chain unavailable"),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::chain::{Config, Profile};
    use std::sync::Mutex;

    #[test]
    fn retries_every_quarter_hour_until_the_previous_pool_closes() {
        let day = 100 * 86_400;
        assert_eq!(next_run(day, 21_600), day + 300);
        assert_eq!(next_run(day + 300, 21_600), day + 1_200);
        assert_eq!(next_run(day + 20_000, 21_600), day + 20_100);
        // Last pass at closure + margin, then the next midnight.
        assert_eq!(next_run(day + 21_000, 21_600), day + 21_900);
        assert_eq!(next_run(day + 21_900, 21_600), day + 86_400 + 300);
        // A closure off the quarter-hour grid still gets its own pass.
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
        // 1 signataire, 0 en lecture signé, owner + config + system + pool 98 + programme en lecture.
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

    struct Fake { profiles: Vec<Profile>, sent: Mutex<Vec<Vec<u8>>> }
    #[async_trait::async_trait]
    impl CrankChain for Fake {
        async fn config(&self) -> Result<Config> {
            Ok(Config { authority: [0; 32], min_stake: 1, decay_bps: 1000, max_decay_days: 30, pool_close_delay: 21_600 })
        }
        async fn profiles(&self) -> Result<Vec<Profile>> { Ok(self.profiles.clone()) }
        async fn latest_blockhash(&self) -> Result<Key> { Ok([6; 32]) }
        async fn send_transaction(&self, raw: &[u8]) -> Result<()> {
            self.sent.lock().unwrap().push(raw.to_vec());
            Ok(())
        }
    }

    /// Every profile is late; the blockhash RPC answers once, then fails.
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
        let now = 101 * 86_400 + 300; // jour 101, 00:05
        let chain = Fake {
            profiles: vec![
                profile(1, 99, 99, true),   // jour 100 manqué
                profile(2, 100, 100, true), // à jour, créance de 100 encore ouverte
                profile(3, 99, -1, false),  // position fermée
            ],
            sent: Mutex::new(vec![]),
        };
        let report = crank_once(&chain, &[9; 32], &SigningKey::from_bytes(&[3; 32]), now).await.unwrap();
        assert_eq!((report.sent, report.failed), (1, 0));
    }
}
