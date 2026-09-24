//! Strict, purpose-limited Solana RPC and legacy check-in co-signing.
use crate::{
    error::{Error, Result},
    protocol::{self, Key, Reader},
};
use async_trait::async_trait;
use ed25519_dalek::{Signer, SigningKey};
use serde_json::{json, Value};
use solana_pubkey::Pubkey;

#[derive(Clone, Debug)]
pub struct Config {
    pub authority: Key,
    pub min_stake: u64,
    pub decay_bps: u16,
    pub max_decay_days: u8,
    /// Seconds after the end of day D before its pool closes (daily pool spec).
    pub pool_close_delay: i64,
}
#[derive(Clone, Debug)]
pub struct Profile {
    pub owner: Key,
    pub staked: u64,
    pub settled_day: i64,
    pub exit_unlock_at: i64,
    pub active: bool,
    /// Claims on the pools of published days, `-1` for a free slot.
    pub pending_days: [i64; 2],
    pub pending_stakes: [u64; 2],
}
#[derive(Clone, Debug)]
pub struct DayPool {
    pub day: i64,
    pub penalties: u64,
    pub total_stake: u64,
}
pub const DAY: i64 = 86_400;
pub fn closes_at(day: i64, delay: i64) -> i64 {
    day.saturating_add(1).saturating_mul(DAY).saturating_add(delay)
}
/// Same formula as the program: pro rata, rounded down, never above the pool.
pub fn share(penalties: u64, stake: u64, total_stake: u64) -> u64 {
    if total_stake == 0 {
        return 0;
    }
    (penalties as u128 * stake as u128 / total_stake as u128).min(penalties as u128) as u64
}
impl Profile {
    pub fn settle_bound(&self, today: i64) -> i64 {
        let yesterday = today.saturating_sub(1);
        if self.exit_unlock_at > 0 {
            yesterday.min(self.exit_unlock_at.div_euclid(DAY) - 1)
        } else {
            yesterday
        }
    }
    /// Claims whose pool is closed, as `(day, stake)`.
    pub fn closed_claims(&self, config: &Config, now: i64) -> Vec<(i64, u64)> {
        (0..2)
            .filter(|&i| {
                self.pending_days[i] >= 0
                    && now >= closes_at(self.pending_days[i], config.pool_close_delay)
            })
            .map(|i| (self.pending_days[i], self.pending_stakes[i]))
            .collect()
    }
    /// Pools a settling instruction must carry, as `(day, writable)`: closed
    /// claims (read) and the last missed day while its pool is open (written).
    pub fn settlement_pools(&self, config: &Config, now: i64, bound: i64) -> Vec<(i64, bool)> {
        let mut pools: Vec<(i64, bool)> = self
            .closed_claims(config, now)
            .into_iter()
            .map(|(day, _)| (day, false))
            .collect();
        if self.active && bound > self.settled_day && now < closes_at(bound, config.pool_close_delay)
        {
            pools.push((bound, true));
        }
        pools
    }
    /// A late day to settle or a closed claim to pay out: `reap` would succeed.
    pub fn needs_reap(&self, config: &Config, now: i64) -> bool {
        self.active
            && (self.settle_bound(now.div_euclid(DAY)) > self.settled_day
                || !self.closed_claims(config, now).is_empty())
    }
    /// `gains` are the closed claims, credited by the program before the decay.
    pub fn eligible(&self, config: &Config, now: i64, gains: u64) -> bool {
        if !self.active
            || config.decay_bps > 10_000
            || (self.exit_unlock_at > 0 && now >= self.exit_unlock_at)
        {
            return false;
        }
        let bound = self.settle_bound(now.div_euclid(DAY));
        let missed = bound.saturating_sub(self.settled_day).max(0);
        let mut remaining = self.staked as u128 + gains as u128;
        if missed > config.max_decay_days as i64 {
            remaining = 0;
        } else {
            for _ in 0..missed {
                remaining = remaining * (10_000 - config.decay_bps as u128) / 10_000;
            }
        }
        remaining >= config.min_stake as u128
    }
}
#[derive(Clone, Debug)]
pub struct CheckIn {
    pub owner: Key,
    pub day: i64,
    pub commitment: Key,
    pub blob_ref: Key,
}
#[async_trait]
pub trait Chain: Send + Sync {
    async fn config(&self) -> Result<Config>;
    async fn profile(&self, wallet: Key) -> Result<Option<Profile>>;
    async fn day_pool(&self, day: i64) -> Result<Option<DayPool>>;
    async fn check_in(&self, wallet: Key, day: i64) -> Result<Option<CheckIn>>;
    async fn blockhash_valid(&self, hash: Key) -> Result<bool>;
}
pub(crate) fn pda(program: &Key, seeds: &[&[u8]]) -> (Key, u8) {
    let (key, bump) = Pubkey::find_program_address(seeds, &Pubkey::new_from_array(*program));
    (key.to_bytes(), bump)
}
pub(crate) fn discriminator(name: &str) -> [u8; 8] {
    protocol::hash(name.as_bytes())[..8].try_into().unwrap()
}
fn invalid() -> Error {
    Error::bad("Transaction non autorisée. Reconstruisez la publication.")
}
pub struct Expected {
    pub program: Key,
    pub wallet: Key,
    pub authority: Key,
    pub day: i64,
    pub commitment: Key,
    pub blob_ref: Key,
    /// Days whose `DayPool` may ride along as a settlement account (claims and
    /// the last missed day). Today's pool is a named account, never an extra.
    pub pools: Vec<i64>,
}

/// ComputeBudget111111111111111111111111111111
const COMPUTE_BUDGET: Key = [
    3, 6, 70, 111, 229, 33, 23, 50, 255, 236, 173, 186, 114, 195, 155, 231, 188, 140, 229, 187,
    197, 247, 18, 107, 44, 67, 155, 58, 64, 0, 0, 0,
];
/// Bounds on priority fees paid by the wallet (the authority never pays).
const MAX_COMPUTE_UNITS: u32 = 400_000;
const MAX_MICRO_LAMPORTS: u64 = 1_000_000;

/// Validates a legacy `check_in` transaction by meaning, not by byte layout:
/// wallets (Seeker, web3.js) re-sort account keys and add priority fees, so
/// the account order may vary. Only one `check_in` plus at most one
/// SetComputeUnitLimit and one SetComputeUnitPrice are accepted.
pub fn validate_transaction(raw: &[u8], expected: &Expected) -> Result<Key> {
    if raw.len() > 1232 {
        return Err(invalid());
    }
    let mut r = Reader::new(raw);
    if r.short()? != 2 {
        return Err(invalid());
    }
    let owner_sig = r.array::<64>()?;
    let authority_sig = r.array::<64>()?;
    let message_offset = r.position();
    let [signers, readonly_signed, readonly_unsigned] = r.array::<3>()?;
    let count = r.short()?;
    if signers != 2 || readonly_signed != 1 || !(8..=12).contains(&count) {
        return Err(invalid());
    }
    let mut keys = Vec::with_capacity(count);
    for _ in 0..count {
        keys.push(r.array::<32>()?);
    }
    for i in 0..count {
        if keys[..i].contains(&keys[i]) {
            return Err(invalid());
        }
    }
    if keys[0] != expected.wallet || keys[1] != expected.authority {
        return Err(invalid());
    }
    let readonly_unsigned = readonly_unsigned as usize;
    if readonly_unsigned + 2 > count {
        return Err(invalid());
    }
    let writable_unsigned = |i: usize| i >= 2 && i < count - readonly_unsigned;
    let readonly_nonsigner = |i: usize| i >= count - readonly_unsigned;
    let hash = r.array()?;
    let instructions = r.short()?;
    if !(1..=3).contains(&instructions) {
        return Err(invalid());
    }
    let config = pda(&expected.program, &[b"config"]).0;
    let profile = pda(&expected.program, &[b"profile", &expected.wallet]).0;
    let checkin = pda(
        &expected.program,
        &[b"checkin", &expected.wallet, &expected.day.to_le_bytes()],
    )
    .0;
    let day_pool = pda(&expected.program, &[b"day_pool", &expected.day.to_le_bytes()]).0;
    let allowed: Vec<Key> = expected
        .pools
        .iter()
        .filter(|day| **day < expected.day)
        .map(|day| pda(&expected.program, &[b"day_pool", &day.to_le_bytes()]).0)
        .collect();
    let mut used = vec![false; count];
    used[0] = true;
    used[1] = true;
    let (mut check_ins, mut limit, mut price) = (0, false, false);
    for _ in 0..instructions {
        let program_index = r.u8()? as usize;
        let program = *keys.get(program_index).ok_or_else(invalid)?;
        if !readonly_nonsigner(program_index) {
            return Err(invalid());
        }
        used[program_index] = true;
        if program == expected.program {
            check_ins += 1;
            let accounts = r.short()?;
            if !(7..=10).contains(&accounts) {
                return Err(invalid());
            }
            let expected_accounts = [
                expected.wallet,
                expected.authority,
                config,
                profile,
                day_pool,
                checkin,
                [0; 32],
            ];
            for (position, key) in expected_accounts.iter().enumerate() {
                let index = r.u8()? as usize;
                if keys.get(index) != Some(key)
                    || match position {
                        0 => index != 0,
                        1 => index != 1,
                        3..=5 => !writable_unsigned(index),
                        // config (no longer written by the program) and system_program
                        _ => !readonly_nonsigner(index),
                    }
                {
                    return Err(invalid());
                }
                used[index] = true;
            }
            // Settlement pools: only the allowed days, each at most once.
            let mut extras: Vec<Key> = Vec::new();
            for _ in 7..accounts {
                let index = r.u8()? as usize;
                let key = *keys.get(index).ok_or_else(invalid)?;
                if index < 2 || !allowed.contains(&key) || extras.contains(&key) {
                    return Err(invalid());
                }
                extras.push(key);
                used[index] = true;
            }
            if r.short()? != 80
                || r.array::<8>()? != discriminator("global:check_in")
                || r.i64()? != expected.day
                || r.array::<32>()? != expected.commitment
                || r.array::<32>()? != expected.blob_ref
            {
                return Err(invalid());
            }
        } else if program == COMPUTE_BUDGET {
            if r.short()? != 0 {
                return Err(invalid());
            }
            match (r.short()?, r.u8()?) {
                (5, 2) if !limit => {
                    limit = true;
                    let units = u32::from_le_bytes(r.array()?);
                    if units > MAX_COMPUTE_UNITS {
                        return Err(invalid());
                    }
                }
                (9, 3) if !price => {
                    price = true;
                    if u64::from_le_bytes(r.array()?) > MAX_MICRO_LAMPORTS {
                        return Err(invalid());
                    }
                }
                _ => return Err(invalid()),
            }
        } else {
            return Err(invalid());
        }
    }
    if check_ins != 1 || used.contains(&false) || !r.done() {
        return Err(invalid());
    }
    if owner_sig != [0; 64] {
        protocol::verify(&expected.wallet, &raw[message_offset..], &owner_sig)
            .map_err(|_| invalid())?;
    }
    if authority_sig != [0; 64] {
        protocol::verify(&expected.authority, &raw[message_offset..], &authority_sig)
            .map_err(|_| invalid())?;
    }
    Ok(hash)
}
pub fn cosign(raw: &[u8], expected: &Expected, signer: &SigningKey) -> Result<Vec<u8>> {
    validate_transaction(raw, expected)?;
    if signer.verifying_key().to_bytes() != expected.authority {
        return Err(Error::unavailable());
    }
    let mut out = raw.to_vec();
    out[65..129].copy_from_slice(&signer.sign(&raw[129..]).to_bytes());
    Ok(out)
}

pub struct RpcChain {
    client: reqwest::Client,
    url: String,
    program: Key,
    /// Comptes récemment lus. Un chargement du feed relit les mêmes PDA (config,
    /// profil, check-ins) pour la liste puis pour chaque blob : sans ce cache, le
    /// RPC public (≈40 getAccountInfo / 10 s par IP) répond 429 dès quelques posts.
    accounts: std::sync::Mutex<std::collections::HashMap<Key, (std::time::Instant, Vec<u8>)>>,
}
/// Assez court pour qu'une mise ou une sortie se voie presque aussitôt.
const ACCOUNT_TTL: std::time::Duration = std::time::Duration::from_secs(5);
/// Réessais après un 429 du RPC, avant d'abandonner en 503.
const RATE_LIMIT_RETRIES: [u64; 2] = [400, 1200];
impl RpcChain {
    pub fn new(url: String, program: Key) -> Result<Self> {
        let parsed = reqwest::Url::parse(&url).map_err(|_| Error::unavailable())?;
        if !matches!(parsed.scheme(), "http" | "https") {
            return Err(Error::unavailable());
        }
        let client = reqwest::Client::builder()
            .timeout(std::time::Duration::from_secs(12))
            .redirect(reqwest::redirect::Policy::none())
            .build()
            .map_err(|_| Error::unavailable())?;
        Ok(Self {
            client,
            url,
            program,
            accounts: Default::default(),
        })
    }
    /// Pin the RPC to its full genesis hash (CAIP-2 network names are truncated).
    /// Verified against api.devnet.solana.com / api.mainnet-beta.solana.com.
    pub async fn verify_network(&self, network: &str) -> Result<()> {
        let expected = match network {
            "devnet" => "EtWTRABZaYq6iMfeYKouRu166VU2xqa1wcaWoxPkrZBG",
            "mainnet" => "5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc147dw2N9d",
            _ => return Err(Error::unavailable()),
        };
        if self.rpc("getGenesisHash", json!([])).await?.as_str() != Some(expected) {
            return Err(Error::unavailable());
        }
        Ok(())
    }
    async fn rpc(&self, method: &str, params: Value) -> Result<Value> {
        self.rpc_limited(method, params, 32_768).await
    }
    async fn rpc_limited(&self, method: &str, params: Value, max_bytes: usize) -> Result<Value> {
        let body = json!({"jsonrpc":"2.0","id":1,"method":method,"params":params});
        let mut retries = RATE_LIMIT_RETRIES.iter();
        let mut response = loop {
            let response = self
                .client
                .post(&self.url)
                .json(&body)
                .send()
                .await
                .map_err(|_| Error::unavailable())?;
            match (response.status(), retries.next()) {
                (reqwest::StatusCode::TOO_MANY_REQUESTS, Some(&delay)) => {
                    tokio::time::sleep(std::time::Duration::from_millis(delay)).await
                }
                _ => break response,
            }
        };
        if !response.status().is_success() {
            return Err(Error::unavailable());
        }
        let mut body = Vec::new();
        while let Some(chunk) = response.chunk().await.map_err(|_| Error::unavailable())? {
            if body.len() + chunk.len() > max_bytes {
                return Err(Error::unavailable());
            }
            body.extend_from_slice(&chunk);
        }
        let value: Value = serde_json::from_slice(&body).map_err(|_| Error::unavailable())?;
        if value.get("error").is_some() {
            return Err(Error::unavailable());
        }
        value.get("result").cloned().ok_or_else(Error::unavailable)
    }
    async fn account(&self, address: Key, name: &str, len: usize) -> Result<Option<Vec<u8>>> {
        if let Some((at, bytes)) = self.accounts.lock().ok().and_then(|c| c.get(&address).cloned()) {
            if at.elapsed() < ACCOUNT_TTL && bytes.len() == len && bytes[..8] == discriminator(name) {
                return Ok(Some(bytes));
            }
        }
        let bytes = self.fetch_account(address, name, len).await?;
        // Seuls les comptes existants sont gardés : un check-in tout juste créé
        // doit être vu dès la lecture suivante.
        if let (Some(bytes), Ok(mut cache)) = (&bytes, self.accounts.lock()) {
            let now = std::time::Instant::now();
            cache.retain(|_, (at, _)| now.duration_since(*at) < ACCOUNT_TTL);
            cache.insert(address, (now, bytes.clone()));
        }
        Ok(bytes)
    }
    async fn fetch_account(&self, address: Key, name: &str, len: usize) -> Result<Option<Vec<u8>>> {
        let result = self.rpc("getAccountInfo", json!([protocol::address_string(&address), {"encoding":"base64","commitment":"confirmed"}])).await?;
        let value = result.get("value").ok_or_else(Error::unavailable)?;
        if value.is_null() {
            return Ok(None);
        }
        if value.get("owner").and_then(Value::as_str)
            != Some(protocol::address_string(&self.program).as_str())
            || value.get("executable").and_then(Value::as_bool) != Some(false)
        {
            return Err(Error::unavailable());
        }
        let data = value
            .get("data")
            .and_then(Value::as_array)
            .ok_or_else(Error::unavailable)?;
        if data.len() != 2 || data[1].as_str() != Some("base64") {
            return Err(Error::unavailable());
        }
        let bytes = protocol::unbase64(data[0].as_str().ok_or_else(Error::unavailable)?, len)
            .map_err(|_| Error::unavailable())?;
        if bytes.len() != len || bytes[..8] != discriminator(name) {
            return Err(Error::unavailable());
        }
        Ok(Some(bytes))
    }
}
#[async_trait]
impl Chain for RpcChain {
    async fn config(&self) -> Result<Config> {
        let (address, bump) = pda(&self.program, &[b"config"]);
        let bytes = self
            .account(address, "account:Config", CONFIG_LEN)
            .await?
            .ok_or_else(Error::unavailable)?;
        let (config, stored_bump) = decode_config(&bytes)?;
        if stored_bump != bump {
            return Err(Error::unavailable());
        }
        Ok(config)
    }
    async fn profile(&self, wallet: Key) -> Result<Option<Profile>> {
        let (address, bump) = pda(&self.program, &[b"profile", &wallet]);
        let Some(bytes) = self.account(address, "account:Profile", PROFILE_LEN).await? else {
            return Ok(None);
        };
        let (profile, stored_bump) = decode_profile(&bytes)?;
        if profile.owner != wallet || stored_bump != bump {
            return Err(Error::unavailable());
        }
        Ok(Some(profile))
    }
    async fn day_pool(&self, day: i64) -> Result<Option<DayPool>> {
        let (address, bump) = pda(&self.program, &[b"day_pool", &day.to_le_bytes()]);
        let Some(bytes) = self.account(address, "account:DayPool", DAY_POOL_LEN).await? else {
            return Ok(None);
        };
        let (pool, stored_bump) = decode_day_pool(&bytes)?;
        if pool.day != day || stored_bump != bump {
            return Err(Error::unavailable());
        }
        Ok(Some(pool))
    }
    async fn check_in(&self, wallet: Key, day: i64) -> Result<Option<CheckIn>> {
        let address = pda(&self.program, &[b"checkin", &wallet, &day.to_le_bytes()]).0;
        let Some(bytes) = self.account(address, "account:CheckIn", 124).await? else {
            return Ok(None);
        };
        let mut r = Reader::new(&bytes);
        r.take(8)?;
        let owner = r.array()?;
        let stored_day = r.i64()?;
        let commitment = r.array()?;
        let blob_ref = r.array()?;
        r.take(12)?;
        if owner != wallet || stored_day != day || !r.done() {
            return Err(Error::unavailable());
        }
        Ok(Some(CheckIn {
            owner,
            day,
            commitment,
            blob_ref,
        }))
    }
    async fn blockhash_valid(&self, hash: Key) -> Result<bool> {
        self.rpc(
            "isBlockhashValid",
            json!([protocol::address_string(&hash), {"commitment":"confirmed"}]),
        )
        .await?
        .get("value")
        .and_then(Value::as_bool)
        .ok_or_else(Error::unavailable)
    }
}

/// Largest `getProgramAccounts` answer the crank accepts (~40k profiles).
const PROFILES_MAX_BYTES: usize = 8 * 1024 * 1024;
#[async_trait]
impl crate::crank::CrankChain for RpcChain {
    async fn config(&self) -> Result<Config> {
        Chain::config(self).await
    }
    async fn profiles(&self) -> Result<Vec<Profile>> {
        let filters = json!([
            {"dataSize": PROFILE_LEN},
            {"memcmp": {"offset": 0, "bytes": bs58::encode(discriminator("account:Profile")).into_string()}}
        ]);
        let result = self
            .rpc_limited(
                "getProgramAccounts",
                json!([protocol::address_string(&self.program), {"encoding":"base64","commitment":"confirmed","filters":filters}]),
                PROFILES_MAX_BYTES,
            )
            .await?;
        let entries = result.as_array().ok_or_else(Error::unavailable)?;
        // One unreadable account must not stop the others from being settled.
        Ok(entries
            .iter()
            .filter_map(|entry| entry["account"]["data"][0].as_str())
            .filter_map(|data| protocol::unbase64(data, PROFILE_LEN).ok())
            .filter_map(|bytes| decode_profile(&bytes).ok())
            .map(|(profile, _)| profile)
            .collect())
    }
    async fn latest_blockhash(&self) -> Result<Key> {
        let result = self
            .rpc("getLatestBlockhash", json!([{"commitment":"confirmed"}]))
            .await?;
        let hash = result["value"]["blockhash"]
            .as_str()
            .ok_or_else(Error::unavailable)?;
        protocol::address(hash).map_err(|_| Error::unavailable())
    }
    async fn send_transaction(&self, raw: &[u8]) -> Result<()> {
        self.rpc(
            "sendTransaction",
            json!([protocol::b64(raw), {"encoding":"base64","preflightCommitment":"confirmed"}]),
        )
        .await
        .map(|_| ())
    }
}

pub const CONFIG_LEN: usize = 174;
pub const PROFILE_LEN: usize = 127;
pub const DAY_POOL_LEN: usize = 37;
/// Anchor layout of `Config`; returns the stored bump, which callers check.
pub fn decode_config(bytes: &[u8]) -> Result<(Config, u8)> {
    if bytes.len() != CONFIG_LEN || bytes[..8] != discriminator("account:Config") {
        return Err(Error::unavailable());
    }
    let mut r = Reader::new(bytes);
    r.take(40)?;
    let authority = r.array()?;
    r.take(64)?;
    let min_stake = r.u64()?;
    r.take(16)?;
    let pool_close_delay = r.i64()?;
    let decay_bps = r.u16()?;
    let max_decay_days = r.u8()?;
    let faucet = r.u8()?;
    let bump = r.u8()?;
    r.u8()?;
    if decay_bps > 10_000 || faucet > 1 || !(0..DAY).contains(&pool_close_delay) || !r.done() {
        return Err(Error::unavailable());
    }
    Ok((
        Config {
            authority,
            min_stake,
            decay_bps,
            max_decay_days,
            pool_close_delay,
        },
        bump,
    ))
}
pub fn decode_profile(bytes: &[u8]) -> Result<(Profile, u8)> {
    if bytes.len() != PROFILE_LEN || bytes[..8] != discriminator("account:Profile") {
        return Err(Error::unavailable());
    }
    let mut r = Reader::new(bytes);
    r.take(8)?;
    let owner = r.array()?;
    let staked = r.u64()?;
    let settled_day = r.i64()?;
    r.take(16)?;
    let exit_unlock_at = r.i64()?;
    r.take(12)?;
    let active = r.u8()?;
    let faucet = r.u8()?;
    let bump = r.u8()?;
    let pending_days = [r.i64()?, r.i64()?];
    let pending_stakes = [r.u64()?, r.u64()?];
    if active > 1 || faucet > 1 || !r.done() {
        return Err(Error::unavailable());
    }
    Ok((
        Profile {
            owner,
            staked,
            settled_day,
            exit_unlock_at,
            active: active == 1,
            pending_days,
            pending_stakes,
        },
        bump,
    ))
}
pub fn decode_day_pool(bytes: &[u8]) -> Result<(DayPool, u8)> {
    if bytes.len() != DAY_POOL_LEN || bytes[..8] != discriminator("account:DayPool") {
        return Err(Error::unavailable());
    }
    let mut r = Reader::new(bytes);
    r.take(8)?;
    let day = r.i64()?;
    let penalties = r.u64()?;
    let total_stake = r.u64()?;
    r.take(4)?;
    let bump = r.u8()?;
    if !r.done() {
        return Err(Error::unavailable());
    }
    Ok((DayPool { day, penalties, total_stake }, bump))
}

#[cfg(test)]
mod tests {
    use super::*;
    fn fixture() -> (Vec<u8>, Expected, SigningKey, SigningKey) {
        let owner = SigningKey::from_bytes(&[11; 32]);
        let authority = SigningKey::from_bytes(&[12; 32]);
        let e = Expected {
            program: [9; 32],
            wallet: owner.verifying_key().to_bytes(),
            authority: authority.verifying_key().to_bytes(),
            day: 20_000,
            commitment: [4; 32],
            blob_ref: [5; 32],
            pools: vec![],
        };
        (check_in_raw(&e, None), e, owner, authority)
    }
    /// Canonical `check_in`: writable PDAs first, then config and system read-only.
    /// `extra` is appended as a read-only account right before the program.
    fn check_in_raw(e: &Expected, extra: Option<Key>) -> Vec<u8> {
        let mut keys = vec![
            e.wallet,
            e.authority,
            pda(&e.program, &[b"profile", &e.wallet]).0,
            pda(&e.program, &[b"day_pool", &e.day.to_le_bytes()]).0,
            pda(&e.program, &[b"checkin", &e.wallet, &e.day.to_le_bytes()]).0,
            pda(&e.program, &[b"config"]).0,
            [0; 32],
        ];
        keys.extend(extra);
        keys.push(e.program);
        let count = keys.len() as u8;
        let mut raw = vec![2];
        raw.extend_from_slice(&[0; 128]);
        raw.extend_from_slice(&[2, 1, count - 5, count]);
        for key in &keys {
            raw.extend_from_slice(key);
        }
        raw.extend_from_slice(&[6; 32]);
        let mut accounts = vec![0, 1, 5, 2, 3, 4, 6];
        if extra.is_some() {
            accounts.push(7);
        }
        raw.extend_from_slice(&[1, count - 1, accounts.len() as u8]);
        raw.extend_from_slice(&accounts);
        raw.push(80);
        raw.extend_from_slice(&discriminator("global:check_in"));
        raw.extend_from_slice(&e.day.to_le_bytes());
        raw.extend_from_slice(&e.commitment);
        raw.extend_from_slice(&e.blob_ref);
        raw
    }
    #[test]
    fn accepts_allowed_pools_and_refuses_the_rest() {
        let (_, mut e, _, _) = fixture();
        e.pools = vec![e.day - 1];
        let allowed = pda(&e.program, &[b"day_pool", &(e.day - 1).to_le_bytes()]).0;
        assert!(validate_transaction(&check_in_raw(&e, Some(allowed)), &e).is_ok());
        assert!(validate_transaction(&check_in_raw(&e, Some([42; 32])), &e).is_err());
    }
    #[test]
    fn refuses_extra_accounts_that_are_not_allowed_pools() {
        let (_, e, _, _) = fixture();
        let pool = pda(&e.program, &[b"day_pool", &(e.day - 1).to_le_bytes()]).0;
        assert!(validate_transaction(&check_in_raw(&e, Some(pool)), &e).is_err());
    }
    #[test]
    fn refuses_todays_pool_as_an_extra_account() {
        let (_, mut e, _, _) = fixture();
        e.pools = vec![e.day];
        let today = pda(&e.program, &[b"day_pool", &e.day.to_le_bytes()]).0;
        assert!(validate_transaction(&check_in_raw(&e, Some(today)), &e).is_err());
    }
    #[test]
    fn cosigning_preserves_wallet_signature_and_message() {
        let (mut raw, e, owner, authority) = fixture();
        let sig = owner.sign(&raw[129..]).to_bytes();
        raw[1..65].copy_from_slice(&sig);
        let signed = cosign(&raw, &e, &authority).unwrap();
        assert_eq!(&signed[..65], &raw[..65]);
        assert_eq!(&signed[129..], &raw[129..]);
        protocol::verify(&e.authority, &signed[129..], &signed[65..129]).unwrap();
        assert_eq!(validate_transaction(&signed, &e).unwrap(), [6; 32]);
    }
    #[test]
    fn refuses_noncanonical_truncated_and_modified_transactions() {
        let (raw, e, _, authority) = fixture();
        assert!(cosign(&raw, &e, &authority).is_ok());
        for end in 0..raw.len() {
            assert!(
                validate_transaction(&raw[..end], &e).is_err(),
                "prefix {end}"
            );
        }
        let mut extra = raw.clone();
        extra.push(0);
        assert!(validate_transaction(&extra, &e).is_err());
        let mut noncanonical = vec![0x82, 0];
        noncanonical.extend_from_slice(&raw[1..]);
        assert!(validate_transaction(&noncanonical, &e).is_err());
        for offset in [
            1, 65, 129, 130, 131, 132, 133, 165, 421, 422, 423, 424, 425, 426, 427, 428, 429, 430,
            431, 432, 442, 482,
        ] {
            let mut tampered = raw.clone();
            tampered[offset] ^= 1;
            assert!(
                validate_transaction(&tampered, &e).is_err(),
                "offset {offset}"
            );
        }
    }
    /// Same shape as the Seeker wallet output: keys re-sorted, priority fees first.
    fn wallet_shaped(e: &Expected, price: u64) -> Vec<u8> {
        let keys = [
            e.wallet,
            e.authority,
            pda(&e.program, &[b"profile", &e.wallet]).0,
            pda(&e.program, &[b"checkin", &e.wallet, &e.day.to_le_bytes()]).0,
            pda(&e.program, &[b"day_pool", &e.day.to_le_bytes()]).0,
            pda(&e.program, &[b"config"]).0,
            COMPUTE_BUDGET,
            e.program,
            [0; 32],
        ];
        let mut raw = vec![2];
        raw.extend_from_slice(&[0; 128]);
        raw.extend_from_slice(&[2, 1, 4, 9]);
        for key in keys {
            raw.extend_from_slice(&key);
        }
        raw.extend_from_slice(&[6; 32]);
        raw.push(3);
        raw.extend_from_slice(&[6, 0, 5, 2]);
        raw.extend_from_slice(&200_000u32.to_le_bytes());
        raw.extend_from_slice(&[6, 0, 9, 3]);
        raw.extend_from_slice(&price.to_le_bytes());
        raw.extend_from_slice(&[7, 7, 0, 1, 5, 2, 4, 3, 8, 80]);
        raw.extend_from_slice(&discriminator("global:check_in"));
        raw.extend_from_slice(&e.day.to_le_bytes());
        raw.extend_from_slice(&e.commitment);
        raw.extend_from_slice(&e.blob_ref);
        raw
    }
    #[test]
    fn accepts_reordered_keys_and_bounded_priority_fees() {
        let (_, e, owner, authority) = fixture();
        let mut raw = wallet_shaped(&e, 100_000);
        let sig = owner.sign(&raw[129..]).to_bytes();
        raw[1..65].copy_from_slice(&sig);
        let signed = cosign(&raw, &e, &authority).unwrap();
        assert_eq!(&signed[..65], &raw[..65]);
        protocol::verify(&e.authority, &signed[129..], &signed[65..129]).unwrap();
        assert!(validate_transaction(&wallet_shaped(&e, MAX_MICRO_LAMPORTS + 1), &e).is_err());
        let mut writable_config = wallet_shaped(&e, 100_000);
        writable_config[131] = 3; // config would become writable
        assert!(validate_transaction(&writable_config, &e).is_err());
    }
    fn layout(name: &str) -> Vec<u8> {
        let text = include_str!("../tests/fixtures/account-layouts-v2.hex");
        let prefix = format!("{name}=");
        let line = text.lines().find(|l| l.starts_with(&prefix)).unwrap();
        hex::decode(&line[prefix.len()..]).unwrap()
    }
    #[test]
    fn decoders_match_the_program_layouts() {
        let (config, bump) = decode_config(&layout("config")).unwrap();
        assert_eq!(config.authority, [2; 32]);
        assert_eq!(config.min_stake, 500_000_000_000);
        assert_eq!(config.pool_close_delay, 21_600);
        assert_eq!((config.decay_bps, config.max_decay_days, bump), (1000, 30, 254));
        let (profile, bump) = decode_profile(&layout("profile")).unwrap();
        assert_eq!(profile.owner, [5; 32]);
        assert_eq!(profile.staked, 123_000_000_000);
        assert_eq!(profile.pending_days, [20_717, 20_718]);
        assert_eq!(profile.pending_stakes, [100_000_000_000, 110_000_000_000]);
        assert!(profile.active);
        assert_eq!(bump, 252);
        let (pool, bump) = decode_day_pool(&layout("day_pool")).unwrap();
        assert_eq!(
            (pool.day, pool.penalties, pool.total_stake, bump),
            (20_718, 30_000_000_000, 600_000_000_000, 251)
        );
    }
    fn pool_config() -> Config {
        Config {
            authority: [0; 32],
            min_stake: 100,
            decay_bps: 1000,
            max_decay_days: 30,
            pool_close_delay: 21_600,
        }
    }
    fn pool_profile() -> Profile {
        Profile {
            owner: [1; 32],
            staked: 1_000,
            settled_day: 99,
            exit_unlock_at: 0,
            active: true,
            pending_days: [98, 99],
            pending_stakes: [1_000, 1_000],
        }
    }
    #[test]
    fn claims_close_the_next_morning() {
        let p = pool_profile();
        let c = pool_config();
        assert_eq!(p.closed_claims(&c, closes_at(98, 21_600) - 1), vec![]);
        assert_eq!(p.closed_claims(&c, closes_at(99, 21_600) - 1), vec![(98, 1_000)]);
        assert_eq!(
            p.closed_claims(&c, closes_at(99, 21_600)),
            vec![(98, 1_000), (99, 1_000)]
        );
    }
    #[test]
    fn settlement_pools_follow_the_program_rule() {
        let c = pool_config();
        let mut p = pool_profile();
        // Published on 98, absent on 99.
        p.settled_day = 98;
        p.pending_days = [98, -1];
        // Day 100 at 03:00: 98 closed (read), 99 missed and still open (written).
        let now = 100 * 86_400 + 3 * 3_600;
        assert_eq!(p.settlement_pools(&c, now, 99), vec![(98, false), (99, true)]);
        // After 06:00 the pool of 99 is closed and no longer passed.
        let now = 100 * 86_400 + 7 * 3_600;
        assert_eq!(p.settlement_pools(&c, now, 99), vec![(98, false)]);
    }
    #[test]
    fn needs_reap_when_late_or_when_a_claim_closed() {
        let c = pool_config();
        let mut p = pool_profile();
        p.pending_days = [-1, 99];
        assert!(!p.needs_reap(&c, 100 * 86_400 + 60));
        assert!(p.needs_reap(&c, 100 * 86_400 + 6 * 3_600 + 60));
        p.pending_days = [-1, -1];
        assert!(p.needs_reap(&c, 101 * 86_400 + 60), "day 100 missed");
        p.active = false;
        assert!(!p.needs_reap(&c, 101 * 86_400 + 60));
    }
    #[test]
    fn closed_gains_count_towards_eligibility() {
        let c = pool_config();
        let mut p = pool_profile();
        p.staked = 95;
        let now = 100 * 86_400;
        assert!(!p.eligible(&c, now, 0));
        assert!(p.eligible(&c, now, 5));
    }
    #[test]
    fn eligibility_matches_compounding_decay_and_unlock_boundary() {
        let config = Config {
            authority: [0; 32],
            min_stake: 56,
            decay_bps: 2500,
            max_decay_days: 30,
            pool_close_delay: 21_600,
        };
        let mut p = Profile {
            owner: [0; 32],
            staked: 100,
            settled_day: 97,
            exit_unlock_at: 0,
            active: true,
            pending_days: [-1, -1],
            pending_stakes: [0, 0],
        };
        assert!(p.eligible(&config, 100 * 86_400, 0)); // floor(75 * .75) = 56
        p.staked = 99;
        assert!(!p.eligible(&config, 100 * 86_400, 0));
        p.staked = 100;
        p.exit_unlock_at = 100 * 86_400 + 500;
        assert!(p.eligible(&config, p.exit_unlock_at - 1, 0));
        assert!(!p.eligible(&config, p.exit_unlock_at, 0));
        p.exit_unlock_at = 0;
        p.settled_day = 68;
        assert!(!p.eligible(&config, 100 * 86_400, 0));
        p.settled_day = 100;
        p.active = false;
        assert!(!p.eligible(&config, 100 * 86_400, 0));
    }
    #[tokio::test]
    async fn rpc_decodes_anchor_layouts_and_rejects_wrong_owner() {
        use axum::{routing::post, Json, Router};
        let program = [9; 32];
        let wallet = [8; 32];
        let day = 20_000i64;
        let (cfg_key, cfg_bump) = pda(&program, &[b"config"]);
        let (profile_key, profile_bump) = pda(&program, &[b"profile", &wallet]);
        let check_key = pda(&program, &[b"checkin", &wallet, &day.to_le_bytes()]).0;
        let mut cfg = vec![0u8; 174];
        cfg[..8].copy_from_slice(&discriminator("account:Config"));
        cfg[40..72].fill(7);
        cfg[136..144].copy_from_slice(&123u64.to_le_bytes());
        cfg[160..168].copy_from_slice(&21_600i64.to_le_bytes());
        cfg[168..170].copy_from_slice(&2500u16.to_le_bytes());
        cfg[170] = 30;
        cfg[172] = cfg_bump;
        let mut profile = vec![0u8; 127];
        profile[..8].copy_from_slice(&discriminator("account:Profile"));
        profile[8..40].copy_from_slice(&wallet);
        profile[40..48].copy_from_slice(&456u64.to_le_bytes());
        profile[48..56].copy_from_slice(&(day - 1).to_le_bytes());
        profile[72..80].copy_from_slice(&123456i64.to_le_bytes());
        profile[92] = 1;
        profile[94] = profile_bump;
        profile[95..103].copy_from_slice(&(day - 1).to_le_bytes());
        profile[103..111].copy_from_slice(&(-1i64).to_le_bytes());
        profile[111..119].copy_from_slice(&456u64.to_le_bytes());
        let mut check = vec![0u8; 124];
        check[..8].copy_from_slice(&discriminator("account:CheckIn"));
        check[8..40].copy_from_slice(&wallet);
        check[40..48].copy_from_slice(&day.to_le_bytes());
        check[48..80].fill(4);
        check[80..112].fill(5);
        let app = Router::new().route("/",post(move |Json(request):Json<Value>| {
            let cfg=cfg.clone(); let profile=profile.clone(); let check=check.clone();
            async move {
                let address = request["params"][0].as_str().unwrap();
                let (data,owner) = if address == protocol::address_string(&cfg_key) {(cfg,program)}
                else if address == protocol::address_string(&profile_key) {(profile,program)}
                else if address == protocol::address_string(&check_key) {(check,program)}
                else {(vec![0;95],[1;32])};
                Json(json!({"result":{"value":{"owner":protocol::address_string(&owner),"executable":false,"data":[protocol::b64(&data),"base64"]}}}))
            }
        }));
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let url = format!("http://{}", listener.local_addr().unwrap());
        let task = tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });
        let chain = RpcChain::new(url, program).unwrap();
        let config = chain.config().await.unwrap();
        assert_eq!(config.authority, [7; 32]);
        assert_eq!(config.min_stake, 123);
        assert_eq!(config.decay_bps, 2500);
        assert_eq!(config.pool_close_delay, 21_600);
        let profile = chain.profile(wallet).await.unwrap().unwrap();
        assert_eq!(profile.pending_days, [day - 1, -1]);
        assert_eq!(profile.pending_stakes, [456, 0]);
        assert_eq!(profile.staked, 456);
        assert_eq!(profile.settled_day, day - 1);
        assert_eq!(profile.exit_unlock_at, 123456);
        assert!(profile.active);
        let check = chain.check_in(wallet, day).await.unwrap().unwrap();
        assert_eq!(check.commitment, [4; 32]);
        assert_eq!(check.blob_ref, [5; 32]);
        assert!(chain.profile([10; 32]).await.is_err());
        task.abort();
    }
    #[tokio::test]
    async fn crank_lists_profiles_and_skips_unreadable_ones() {
        use crate::crank::CrankChain;
        let good = protocol::b64(&layout("profile"));
        let (chain, task) = mock_rpc(json!({"result": [
            {"pubkey": "x", "account": {"data": [good, "base64"]}},
            {"pubkey": "y", "account": {"data": [protocol::b64(&[1, 2, 3]), "base64"]}},
        ]}))
        .await;
        let profiles = chain.profiles().await.unwrap();
        assert_eq!(profiles.len(), 1);
        assert_eq!(profiles[0].owner, [5; 32]);
        task.abort();
    }
    async fn mock_rpc(response: Value) -> (RpcChain, tokio::task::JoinHandle<()>) {
        use axum::{routing::post, Json, Router};
        let app = Router::new().route(
            "/",
            post(move || {
                let response = response.clone();
                async move { Json(response) }
            }),
        );
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let url = format!("http://{}", listener.local_addr().unwrap());
        let task = tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });
        (RpcChain::new(url, [9; 32]).unwrap(), task)
    }
    #[tokio::test]
    async fn network_identity_requires_full_expected_genesis_hash() {
        for (network, hash) in [
            ("devnet", "EtWTRABZaYq6iMfeYKouRu166VU2xqa1wcaWoxPkrZBG"),
            ("mainnet", "5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc147dw2N9d"),
        ] {
            let (chain, task) = mock_rpc(json!({"result":hash})).await;
            assert!(chain.verify_network(network).await.is_ok());
            assert!(chain
                .verify_network(if network == "devnet" {
                    "mainnet"
                } else {
                    "devnet"
                })
                .await
                .is_err());
            assert!(chain.verify_network("custom").await.is_err());
            task.abort();
        }
        for result in [
            json!("EtWTRABZaYq6iMfeYKouRu166VU2xqa1"),
            json!(null),
            json!({"hash":"invalid"}),
        ] {
            let (chain, task) = mock_rpc(json!({"result":result})).await;
            assert!(chain.verify_network("devnet").await.is_err());
            task.abort();
        }
    }
    #[tokio::test]
    async fn rpc_handles_valid_blockhash_missing_accounts_and_malformed_data() {
        for value in [true, false] {
            let (chain, task) = mock_rpc(json!({"result":{"value":value}})).await;
            assert_eq!(chain.blockhash_valid([6; 32]).await.unwrap(), value);
            task.abort();
        }
        let (chain, task) = mock_rpc(json!({"result":{"value":null}})).await;
        assert!(chain.profile([8; 32]).await.unwrap().is_none());
        assert!(chain.config().await.is_err());
        task.abort();
        for response in [
            json!({}),
            json!({"result":{}}),
            json!({"result":{"value":"true"}}),
            json!({"error":{"message":"secret"}}),
        ] {
            let (chain, task) = mock_rpc(response).await;
            assert!(chain.blockhash_valid([6; 32]).await.is_err());
            task.abort();
        }
        for len in [7, 94, 95, 96] {
            // Includes exact length with a wrong discriminator, plus truncated/oversized data.
            let response = json!({"result":{"value":{"owner":protocol::address_string(&[9;32]),"executable":false,"data":[protocol::b64(&vec![0;len]),"base64"]}}});
            let (chain, task) = mock_rpc(response).await;
            assert!(chain.profile([8; 32]).await.is_err());
            task.abort();
        }
    }
    #[test]
    fn eligibility_is_bounded_at_integer_extremes() {
        let mut config = Config {
            authority: [0; 32],
            min_stake: 1,
            decay_bps: 10_000,
            max_decay_days: 255,
            pool_close_delay: 21_600,
        };
        let mut profile = Profile {
            owner: [0; 32],
            staked: u64::MAX,
            settled_day: i64::MIN,
            exit_unlock_at: 0,
            active: true,
            pending_days: [-1, -1],
            pending_stakes: [0, 0],
        };
        assert!(!profile.eligible(&config, i64::MAX, 0));
        assert!(!profile.eligible(&config, i64::MIN, 0));
        profile.settled_day = i64::MAX;
        assert!(profile.eligible(&config, i64::MAX, 0));
        config.decay_bps = u16::MAX;
        assert!(!profile.eligible(&config, 0, 0));
    }
}
