//! Daily crank: settles late profiles and pays out closed pools.
//! Permissionless on-chain; this server only pays the fees.
use crate::{
    chain::{self, Config, Profile, RpcChain, DAY},
    error::Result,
    protocol::Key,
};
use async_trait::async_trait;
use ed25519_dalek::{Signer, SigningKey};
use std::sync::Arc;

/// Five minutes after midnight (late profiles), five minutes after closure (payouts).
const MARGIN: i64 = 300;

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
        .flat_map(|day| [day * DAY + MARGIN, day * DAY + close_delay + MARGIN])
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

/// Idempotent: a profile with nothing to reap is skipped, and a failed send
/// is retried at the next run.
pub async fn crank_once(chain: &dyn CrankChain, program: &Key, signer: &SigningKey, now: i64) -> Result<CrankReport> {
    let config = chain.config().await?;
    let today = now.div_euclid(DAY);
    let payer = signer.verifying_key().to_bytes();
    let mut report = CrankReport::default();
    let mut blockhash = chain.latest_blockhash().await?;
    for (n, profile) in chain.profiles().await?.into_iter().enumerate() {
        if profile.owner == payer || !profile.needs_reap(&config, now) {
            continue;
        }
        if n > 0 && n % 100 == 0 {
            blockhash = chain.latest_blockhash().await?;
        }
        let pools = profile.settlement_pools(&config, now, profile.settle_bound(today));
        let raw = reap_transaction(program, signer, &profile.owner, today, &pools, &blockhash);
        match chain.send_transaction(&raw).await {
            Ok(()) => report.sent += 1,
            Err(_) => report.failed += 1,
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
    fn runs_after_midnight_then_after_closure() {
        let day = 100 * 86_400;
        assert_eq!(next_run(day, 21_600), day + 300);
        assert_eq!(next_run(day + 300, 21_600), day + 21_600 + 300);
        assert_eq!(next_run(day + 21_600 + 300, 21_600), day + 86_400 + 300);
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
