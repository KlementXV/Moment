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
}
#[derive(Clone, Debug)]
pub struct Profile {
    pub owner: Key,
    pub staked: u64,
    pub settled_day: i64,
    pub exit_unlock_at: i64,
    pub active: bool,
}
impl Profile {
    pub fn eligible(&self, config: &Config, now: i64) -> bool {
        if !self.active
            || config.decay_bps > 10_000
            || (self.exit_unlock_at > 0 && now >= self.exit_unlock_at)
        {
            return false;
        }
        let yesterday = now.div_euclid(86_400) - 1;
        let bound = if self.exit_unlock_at > 0 {
            yesterday.min(self.exit_unlock_at.div_euclid(86_400) - 1)
        } else {
            yesterday
        };
        let missed = bound.saturating_sub(self.settled_day).max(0);
        let mut remaining = self.staked as u128;
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
    async fn check_in(&self, wallet: Key, day: i64) -> Result<Option<CheckIn>>;
    async fn blockhash_valid(&self, hash: Key) -> Result<bool>;
}
fn pda(program: &Key, seeds: &[&[u8]]) -> (Key, u8) {
    let (key, bump) = Pubkey::find_program_address(seeds, &Pubkey::new_from_array(*program));
    (key.to_bytes(), bump)
}
fn discriminator(name: &str) -> [u8; 8] {
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
}

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
    // Exactly two signers, authority read-only; exactly system and program read-only unsigned.
    if r.array::<3>()? != [2, 1, 2] || r.short()? != 7 {
        return Err(invalid());
    }
    let mut keys = Vec::with_capacity(7);
    for _ in 0..7 {
        keys.push(r.array::<32>()?);
    }
    let config = pda(&expected.program, &[b"config"]).0;
    let profile = pda(&expected.program, &[b"profile", &expected.wallet]).0;
    let checkin = pda(
        &expected.program,
        &[b"checkin", &expected.wallet, &expected.day.to_le_bytes()],
    )
    .0;
    if keys[0] != expected.wallet || keys[1] != expected.authority {
        return Err(invalid());
    }
    for i in 0..7 {
        if keys[..i].contains(&keys[i]) {
            return Err(invalid());
        }
    }
    let hash = r.array()?;
    if r.short()? != 1 {
        return Err(invalid());
    }
    let program_index = r.u8()? as usize;
    if keys.get(program_index) != Some(&expected.program) || program_index < 5 || r.short()? != 6 {
        return Err(invalid());
    }
    let expected_accounts = [
        expected.wallet,
        expected.authority,
        config,
        profile,
        checkin,
        [0; 32],
    ];
    for (position, key) in expected_accounts.iter().enumerate() {
        let index = r.u8()? as usize;
        if keys.get(index) != Some(key)
            || match position {
                0 => index != 0,
                1 => index != 1,
                2..=4 => !(2..5).contains(&index),
                _ => index < 5,
            }
        {
            return Err(invalid());
        }
    }
    if r.short()? != 80
        || r.array::<8>()? != discriminator("global:check_in")
        || r.i64()? != expected.day
        || r.array::<32>()? != expected.commitment
        || r.array::<32>()? != expected.blob_ref
        || !r.done()
    {
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
}
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
        let mut response = self
            .client
            .post(&self.url)
            .json(&json!({"jsonrpc":"2.0","id":1,"method":method,"params":params}))
            .send()
            .await
            .map_err(|_| Error::unavailable())?;
        if !response.status().is_success() {
            return Err(Error::unavailable());
        }
        let mut body = Vec::new();
        while let Some(chunk) = response.chunk().await.map_err(|_| Error::unavailable())? {
            if body.len() + chunk.len() > 32_768 {
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
            .account(address, "account:Config", 184)
            .await?
            .ok_or_else(Error::unavailable)?;
        let mut r = Reader::new(&bytes);
        r.take(40)?;
        let authority = r.array()?;
        r.take(72)?;
        let min_stake = r.u64()?;
        r.take(26)?;
        let decay_bps = r.u16()?;
        let max_decay_days = r.u8()?;
        let faucet = r.u8()?;
        let stored_bump = r.u8()?;
        r.u8()?;
        if decay_bps > 10_000 || faucet > 1 || stored_bump != bump || !r.done() {
            return Err(Error::unavailable());
        }
        Ok(Config {
            authority,
            min_stake,
            decay_bps,
            max_decay_days,
        })
    }
    async fn profile(&self, wallet: Key) -> Result<Option<Profile>> {
        let (address, bump) = pda(&self.program, &[b"profile", &wallet]);
        let Some(bytes) = self.account(address, "account:Profile", 95).await? else {
            return Ok(None);
        };
        let mut r = Reader::new(&bytes);
        r.take(8)?;
        let owner = r.array()?;
        let staked = r.u64()?;
        let settled_day = r.i64()?;
        r.take(16)?;
        let exit_unlock_at = r.i64()?;
        r.take(12)?;
        let active = r.u8()?;
        let faucet = r.u8()?;
        let stored_bump = r.u8()?;
        if owner != wallet || active > 1 || faucet > 1 || stored_bump != bump || !r.done() {
            return Err(Error::unavailable());
        }
        Ok(Some(Profile {
            owner,
            staked,
            settled_day,
            exit_unlock_at,
            active: active == 1,
        }))
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
        };
        let keys = [
            e.wallet,
            e.authority,
            pda(&e.program, &[b"config"]).0,
            pda(&e.program, &[b"profile", &e.wallet]).0,
            pda(&e.program, &[b"checkin", &e.wallet, &e.day.to_le_bytes()]).0,
            [0; 32],
            e.program,
        ];
        let mut raw = vec![2];
        raw.extend_from_slice(&[0; 128]);
        raw.extend_from_slice(&[2, 1, 2, 7]);
        for key in keys {
            raw.extend_from_slice(&key);
        }
        raw.extend_from_slice(&[6; 32]);
        raw.extend_from_slice(&[1, 6, 6, 0, 1, 2, 3, 4, 5, 80]);
        raw.extend_from_slice(&discriminator("global:check_in"));
        raw.extend_from_slice(&e.day.to_le_bytes());
        raw.extend_from_slice(&e.commitment);
        raw.extend_from_slice(&e.blob_ref);
        (raw, e, owner, authority)
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
            1, 65, 129, 130, 131, 132, 133, 165, 389, 390, 391, 392, 393, 394, 395, 396, 397, 398,
            399, 400, 410, 450,
        ] {
            let mut tampered = raw.clone();
            tampered[offset] ^= 1;
            assert!(
                validate_transaction(&tampered, &e).is_err(),
                "offset {offset}"
            );
        }
    }
    #[test]
    fn eligibility_matches_compounding_decay_and_unlock_boundary() {
        let config = Config {
            authority: [0; 32],
            min_stake: 56,
            decay_bps: 2500,
            max_decay_days: 30,
        };
        let mut p = Profile {
            owner: [0; 32],
            staked: 100,
            settled_day: 97,
            exit_unlock_at: 0,
            active: true,
        };
        assert!(p.eligible(&config, 100 * 86_400)); // floor(75 * .75) = 56
        p.staked = 99;
        assert!(!p.eligible(&config, 100 * 86_400));
        p.staked = 100;
        p.exit_unlock_at = 100 * 86_400 + 500;
        assert!(p.eligible(&config, p.exit_unlock_at - 1));
        assert!(!p.eligible(&config, p.exit_unlock_at));
        p.exit_unlock_at = 0;
        p.settled_day = 68;
        assert!(!p.eligible(&config, 100 * 86_400));
        p.settled_day = 100;
        p.active = false;
        assert!(!p.eligible(&config, 100 * 86_400));
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
        let mut cfg = vec![0u8; 184];
        cfg[..8].copy_from_slice(&discriminator("account:Config"));
        cfg[40..72].fill(7);
        cfg[144..152].copy_from_slice(&123u64.to_le_bytes());
        cfg[178..180].copy_from_slice(&2500u16.to_le_bytes());
        cfg[180] = 30;
        cfg[182] = cfg_bump;
        let mut profile = vec![0u8; 95];
        profile[..8].copy_from_slice(&discriminator("account:Profile"));
        profile[8..40].copy_from_slice(&wallet);
        profile[40..48].copy_from_slice(&456u64.to_le_bytes());
        profile[48..56].copy_from_slice(&(day - 1).to_le_bytes());
        profile[72..80].copy_from_slice(&123456i64.to_le_bytes());
        profile[92] = 1;
        profile[94] = profile_bump;
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
        let profile = chain.profile(wallet).await.unwrap().unwrap();
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
        };
        let mut profile = Profile {
            owner: [0; 32],
            staked: u64::MAX,
            settled_day: i64::MIN,
            exit_unlock_at: 0,
            active: true,
        };
        assert!(!profile.eligible(&config, i64::MAX));
        assert!(!profile.eligible(&config, i64::MIN));
        profile.settled_day = i64::MAX;
        assert!(profile.eligible(&config, i64::MAX));
        config.decay_bps = u16::MAX;
        assert!(!profile.eligible(&config, 0));
    }
}
