use async_trait::async_trait;
use axum::{
    body::Body,
    http::{Request, StatusCode},
    Router,
};
use ed25519_dalek::{Signer, SigningKey};
use http_body_util::BodyExt;
use moment_keyserver::{
    api::{self, App, Clock},
    chain::{Chain, CheckIn, Config, DayPool, Profile},
    error::{Error, Result},
    moderation::Reviewer,
    protocol::{self, Key},
    store::ObjectBlobs,
};
use serde_json::{json, Value};
use solana_pubkey::Pubkey;
use std::{
    collections::HashMap,
    sync::{
        atomic::{AtomicBool, AtomicI64, AtomicUsize, Ordering},
        Arc, Mutex,
    },
};
use tokio::sync::Semaphore;
use tower::ServiceExt;

mod support;

const DAY: i64 = 20706;
const PROGRAM: Key = [19; 32];
struct TestClock(AtomicI64);
impl Clock for TestClock {
    fn now(&self) -> i64 {
        self.0.load(Ordering::SeqCst)
    }
}
struct TestChain {
    config: Config,
    profiles: Mutex<HashMap<Key, Profile>>,
    checkins: Mutex<HashMap<(Key, i64), CheckIn>>,
    pools: Mutex<HashMap<i64, DayPool>>,
    available: AtomicBool,
    delayed_reads: AtomicBool,
    fail_read: Mutex<Option<Key>>,
    active_reads: AtomicUsize,
    peak_reads: AtomicUsize,
    blockhash: AtomicBool,
    advance_after_reads: Mutex<Option<(usize, Arc<TestClock>, i64)>>,
}
impl TestChain {
    fn ready(&self) -> Result<()> {
        if self.available.load(Ordering::SeqCst) {
            Ok(())
        } else {
            Err(Error::unavailable())
        }
    }
}
#[async_trait]
impl Chain for TestChain {
    async fn config(&self) -> Result<Config> {
        self.ready()?;
        Ok(self.config.clone())
    }
    async fn profile(&self, wallet: Key) -> Result<Option<Profile>> {
        self.ready()?;
        Ok(self.profiles.lock().unwrap().get(&wallet).cloned())
    }
    async fn day_pool(&self, day: i64) -> Result<Option<DayPool>> {
        self.ready()?;
        Ok(self.pools.lock().unwrap().get(&day).cloned())
    }
    async fn check_in(&self, wallet: Key, day: i64) -> Result<Option<CheckIn>> {
        self.ready()?;
        if self.delayed_reads.load(Ordering::SeqCst) {
            let active = self.active_reads.fetch_add(1, Ordering::SeqCst) + 1;
            self.peak_reads.fetch_max(active, Ordering::SeqCst);
            struct Reading<'a>(&'a AtomicUsize);
            impl Drop for Reading<'_> {
                fn drop(&mut self) {
                    self.0.fetch_sub(1, Ordering::SeqCst);
                }
            }
            let _reading = Reading(&self.active_reads);
            tokio::time::sleep(std::time::Duration::from_millis(
                20 + u64::from(wallet[0] % 8),
            ))
            .await;
        }
        if *self.fail_read.lock().unwrap() == Some(wallet) {
            return Err(Error::unavailable());
        }
        let mut advance = self.advance_after_reads.lock().unwrap();
        if let Some((remaining, clock, timestamp)) = advance.as_mut() {
            *remaining -= 1;
            if *remaining == 0 {
                clock.0.store(*timestamp, Ordering::SeqCst);
                *advance = None;
            }
        }
        Ok(self.checkins.lock().unwrap().get(&(wallet, day)).cloned())
    }
    async fn blockhash_valid(&self, _: Key) -> Result<bool> {
        self.ready()?;
        Ok(self.blockhash.load(Ordering::SeqCst))
    }
}
struct TestReviewer(AtomicBool);
impl Reviewer for TestReviewer {
    fn review(&self, _: &[u8], _: &[u8]) -> Result<()> {
        if self.0.load(Ordering::SeqCst) {
            Ok(())
        } else {
            Err(Error(
                StatusCode::UNPROCESSABLE_ENTITY,
                "Photos refusées. Reprenez-les.",
            ))
        }
    }
}
struct Harness {
    router: Router,
    app: Arc<App>,
    clock: Arc<TestClock>,
    chain: Arc<TestChain>,
    reviewer: Arc<TestReviewer>,
    _dir: tempfile::TempDir,
    _database: support::Database,
}
impl Harness {
    async fn new() -> Self {
        let authority = SigningKey::from_bytes(&[17; 32]);
        let chain = Arc::new(TestChain {
            config: Config {
                authority: authority.verifying_key().to_bytes(),
                min_stake: 10,
                decay_bps: 2500,
                max_decay_days: 30,
                pool_close_delay: 21_600,
            },
            profiles: Mutex::new(HashMap::new()),
            checkins: Mutex::new(HashMap::new()),
            pools: Mutex::new(HashMap::new()),
            available: AtomicBool::new(true),
            delayed_reads: AtomicBool::new(false),
            fail_read: Mutex::new(None),
            active_reads: AtomicUsize::new(0),
            peak_reads: AtomicUsize::new(0),
            blockhash: AtomicBool::new(true),
            advance_after_reads: Mutex::new(None),
        });
        let clock = Arc::new(TestClock(AtomicI64::new(DAY * 86400 + 100)));
        let reviewer = Arc::new(TestReviewer(AtomicBool::new(true)));
        let dir = tempfile::tempdir().unwrap();
        let database = support::Database::create([3; 32]).await;
        let app = Arc::new(App {
            db: database.db.clone(),
            blobs: Arc::new(ObjectBlobs::local(dir.path().to_str().unwrap()).unwrap()),
            chain: chain.clone(),
            reviewer: reviewer.clone(),
            clock: clock.clone(),
            network: "devnet".into(),
            program: PROGRAM,
            origin: "https://moment.example".into(),
            authority,
            inference_slots: Arc::new(Semaphore::new(1)),
        });
        Self {
            router: api::router(app.clone()),
            app,
            clock,
            chain,
            reviewer,
            _dir: dir,
            _database: database,
        }
    }
    async fn request(
        &self,
        method: &str,
        path: &str,
        token: Option<&str>,
        body: Value,
    ) -> (StatusCode, Value) {
        let mut request = Request::builder()
            .method(method)
            .uri(path)
            .header("content-type", "application/json");
        if let Some(token) = token {
            request = request.header("authorization", format!("Bearer {token}"));
        }
        let response = self
            .router
            .clone()
            .oneshot(request.body(Body::from(body.to_string())).unwrap())
            .await
            .unwrap();
        let status = response.status();
        assert_eq!(response.headers()["cache-control"], "no-store, private");
        let bytes = response.into_body().collect().await.unwrap().to_bytes();
        let value = serde_json::from_slice(&bytes)
            .unwrap_or_else(|_| json!({"bytes":protocol::b64(&bytes)}));
        (status, value)
    }
    async fn auth(&self, user: &SigningKey) -> String {
        let wallet = user.verifying_key().to_bytes();
        self.chain.profiles.lock().unwrap().insert(
            wallet,
            Profile {
                owner: wallet,
                staked: 50,
                settled_day: DAY - 1,
                exit_unlock_at: 0,
                active: true,
                pending_days: [-1, -1],
                pending_stakes: [0, 0],
            },
        );
        let (status, challenge) = self
            .request(
                "POST",
                "/v1/session/challenge",
                None,
                json!({"wallet":protocol::address_string(&wallet)}),
            )
            .await;
        assert_eq!(status, StatusCode::OK);
        assert!(challenge["message"]
            .as_str()
            .unwrap()
            .contains("origine: https://moment.example"));
        let signature = user
            .sign(challenge["message"].as_str().unwrap().as_bytes())
            .to_bytes();
        let(status,session)=self.request("POST","/v1/session/verify",None,json!({"wallet":protocol::address_string(&wallet),"nonce":challenge["nonce"],"signature":protocol::b64(&signature)})).await;
        assert_eq!(status, StatusCode::OK, "{session}");
        session["token"].as_str().unwrap().into()
    }
    fn submission(&self, user: &SigningKey, salt: u8) -> Value {
        let wallet = user.verifying_key().to_bytes();
        let rear = vec![1, 2, 3, salt];
        let front = vec![4, 5, 6, salt];
        let manifest = manifest(PROGRAM, wallet, DAY, [salt; 16], &rear, &front);
        let signature = user.sign(&manifest).to_bytes();
        let mut packet = protocol::PACKET_DOMAIN.to_vec();
        // Odd salts use packet v2 (with caption), even salts keep v1 covered.
        let caption: &[u8] = "Légende ✓".as_bytes();
        packet.push(if salt % 2 == 1 { 2 } else { 1 });
        for bytes in [&manifest[..], &signature, &rear, &front] {
            packet.extend_from_slice(&(bytes.len() as u32).to_le_bytes());
            packet.extend_from_slice(bytes);
        }
        if salt % 2 == 1 {
            packet.extend_from_slice(&(caption.len() as u32).to_le_bytes());
            packet.extend_from_slice(caption);
        }
        let key = [salt; 32];
        let blob = protocol::seal(&packet, &key, protocol::PACKET_DOMAIN).unwrap();
        let commitment = protocol::hash(&manifest);
        let blob_ref = protocol::hash(&blob);
        let tx = transaction(
            PROGRAM,
            wallet,
            self.app.authority.verifying_key().to_bytes(),
            DAY,
            commitment,
            blob_ref,
            [1; 32],
        );
        json!({"day":DAY,"commitment":hex::encode(commitment),"blobRef":hex::encode(blob_ref),"postKey":protocol::b64(&key),"blob":protocol::b64(&blob),"transaction":protocol::b64(&tx)})
    }
    fn onchain(&self, user: &SigningKey, submission: &Value) {
        let wallet = user.verifying_key().to_bytes();
        self.chain.checkins.lock().unwrap().insert(
            (wallet, DAY),
            CheckIn {
                owner: wallet,
                day: DAY,
                commitment: protocol::hash_hex(submission["commitment"].as_str().unwrap()).unwrap(),
                blob_ref: protocol::hash_hex(submission["blobRef"].as_str().unwrap()).unwrap(),
            },
        );
    }
    async fn publish(&self, user: &SigningKey, token: &str, salt: u8) -> Value {
        let input = self.submission(user, salt);
        let (status, response) = self
            .request("POST", "/v1/posts", Some(token), input.clone())
            .await;
        assert_eq!(status, StatusCode::OK, "{response}");
        self.onchain(user, &input);
        let (status, response) = self
            .request(
                "POST",
                &format!(
                    "/v1/posts/{}/confirm",
                    input["commitment"].as_str().unwrap()
                ),
                Some(token),
                json!({}),
            )
            .await;
        assert_eq!(status, StatusCode::OK, "{response}");
        input
    }
}
fn manifest(
    program: Key,
    wallet: Key,
    day: i64,
    nonce: [u8; 16],
    rear: &[u8],
    front: &[u8],
) -> Vec<u8> {
    let mut m = protocol::MANIFEST_DOMAIN.to_vec();
    m.extend_from_slice(&[1, 6]);
    m.extend_from_slice(b"devnet");
    m.extend_from_slice(&program);
    m.extend_from_slice(&wallet);
    m.extend_from_slice(&day.to_le_bytes());
    m.extend_from_slice(&nonce);
    m.extend_from_slice(&protocol::hash(rear));
    m.extend_from_slice(&protocol::hash(front));
    m
}
fn transaction(
    program: Key,
    wallet: Key,
    authority: Key,
    day: i64,
    commitment: Key,
    blob_ref: Key,
    blockhash: Key,
) -> Vec<u8> {
    let p = Pubkey::new_from_array(program);
    let keys = [
        wallet,
        authority,
        Pubkey::find_program_address(&[b"config"], &p).0.to_bytes(),
        Pubkey::find_program_address(&[b"profile", &wallet], &p)
            .0
            .to_bytes(),
        Pubkey::find_program_address(&[b"checkin", &wallet, &day.to_le_bytes()], &p)
            .0
            .to_bytes(),
        [0; 32],
        program,
    ];
    let mut tx = vec![2];
    tx.extend_from_slice(&[0; 128]);
    tx.extend_from_slice(&[2, 1, 2, 7]);
    for key in keys {
        tx.extend_from_slice(&key);
    }
    tx.extend_from_slice(&blockhash);
    tx.extend_from_slice(&[1, 6, 6, 0, 1, 2, 3, 4, 5, 80]);
    tx.extend_from_slice(&protocol::hash(b"global:check_in")[..8]);
    tx.extend_from_slice(&day.to_le_bytes());
    tx.extend_from_slice(&commitment);
    tx.extend_from_slice(&blob_ref);
    tx
}

#[test]
fn manifest_replays_android_golden_commitment() {
    let m = manifest(
        [1; 32],
        [2; 32],
        20706,
        [3; 16],
        "arrière".as_bytes(),
        b"selfie",
    );
    assert_eq!(m.len(), 175);
    assert_eq!(
        hex::encode(protocol::hash(&m)),
        "cd6e5720f11bf646617ef9ebf69bf2cfdf656cc36f1421e9cf6954fd8e69f093"
    );
}

#[tokio::test]
async fn two_wallets_publish_confirm_and_decrypt_but_lurker_cannot_read() {
    let h = Harness::new().await;
    let alice = SigningKey::from_bytes(&[1; 32]);
    let bob = SigningKey::from_bytes(&[2; 32]);
    let lurker = SigningKey::from_bytes(&[3; 32]);
    let a = h.auth(&alice).await;
    let b = h.auth(&bob).await;
    let l = h.auth(&lurker).await;
    let a_post = h.publish(&alice, &a, 10).await;
    h.publish(&bob, &b, 11).await;
    let path = format!("/v1/feed?day={DAY}");
    let (status, feed) = h.request("GET", &path, Some(&b), json!(null)).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(feed["items"].as_array().unwrap().len(), 2);
    let alice_item = feed["items"]
        .as_array()
        .unwrap()
        .iter()
        .find(|p| p["commitment"] == a_post["commitment"])
        .unwrap();
    let (status, blob) = h
        .request(
            "GET",
            alice_item["blobUrl"].as_str().unwrap(),
            Some(&b),
            json!(null),
        )
        .await;
    assert_eq!(status, StatusCode::OK);
    let packet = protocol::Packet::decrypt(
        &protocol::unbase64(blob["bytes"].as_str().unwrap(), protocol::MAX_BLOB).unwrap(),
        &protocol::key64(alice_item["postKey"].as_str().unwrap()).unwrap(),
    )
    .unwrap();
    packet
        .verify("devnet", &PROGRAM, &alice.verifying_key().to_bytes(), DAY)
        .unwrap();
    assert_eq!(
        h.request("GET", &path, Some(&l), json!(null)).await.0,
        StatusCode::FORBIDDEN
    );
    assert_eq!(
        h.request(
            "GET",
            alice_item["blobUrl"].as_str().unwrap(),
            Some(&l),
            json!(null)
        )
        .await
        .0,
        StatusCode::FORBIDDEN
    );
    assert_eq!(
        h.request(
            "GET",
            alice_item["blobUrl"].as_str().unwrap(),
            None,
            json!(null)
        )
        .await
        .0,
        StatusCode::UNAUTHORIZED
    );
    let (status, page) = h
        .request("GET", &format!("{path}&limit=1"), Some(&b), json!(null))
        .await;
    assert_eq!(status, StatusCode::OK);
    let (_, next) = h
        .request(
            "GET",
            &format!(
                "{path}&limit=1&after={}",
                page["nextCursor"].as_str().unwrap()
            ),
            Some(&b),
            json!(null),
        )
        .await;
    assert_eq!(next["items"].as_array().unwrap().len(), 1);
    assert_ne!(
        page["items"][0]["commitment"],
        next["items"][0]["commitment"]
    );
    assert!(next["nextCursor"].is_null());
}

#[tokio::test]
async fn members_like_and_unlike_todays_posts_but_lurkers_cannot() {
    let h = Harness::new().await;
    let alice = SigningKey::from_bytes(&[1; 32]);
    let bob = SigningKey::from_bytes(&[2; 32]);
    let lurker = SigningKey::from_bytes(&[3; 32]);
    let a = h.auth(&alice).await;
    let b = h.auth(&bob).await;
    let l = h.auth(&lurker).await;
    let post = h.publish(&alice, &a, 11).await;
    h.publish(&bob, &b, 12).await;
    let like = format!("/v1/posts/{}/like", post["commitment"].as_str().unwrap());
    for _ in 0..2 {
        let (status, body) = h.request("PUT", &like, Some(&b), json!(null)).await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(body, json!({"likes": 1, "liked": true}));
    }
    assert_eq!(
        h.request("PUT", &like, Some(&l), json!(null)).await.0,
        StatusCode::FORBIDDEN
    );
    assert_eq!(
        h.request("PUT", &like, None, json!(null)).await.0,
        StatusCode::UNAUTHORIZED
    );
    let unknown = format!("/v1/posts/{}/like", "ab".repeat(32));
    assert_eq!(
        h.request("PUT", &unknown, Some(&b), json!(null)).await.0,
        StatusCode::NOT_FOUND
    );
    let path = format!("/v1/feed?day={DAY}");
    let item = |feed: &Value| {
        feed["items"]
            .as_array()
            .unwrap()
            .iter()
            .find(|p| p["commitment"] == post["commitment"])
            .cloned()
            .unwrap()
    };
    let (_, feed) = h.request("GET", &path, Some(&a), json!(null)).await;
    assert_eq!(
        (item(&feed)["likes"].clone(), item(&feed)["liked"].clone()),
        (json!(1), json!(false))
    );
    let (_, feed) = h.request("GET", &path, Some(&b), json!(null)).await;
    assert_eq!(item(&feed)["liked"], json!(true));
    let (status, body) = h.request("DELETE", &like, Some(&b), json!(null)).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body, json!({"likes": 0, "liked": false}));
}

#[tokio::test]
async fn pending_mismatch_and_reorg_never_release_keys_or_blobs() {
    let h = Harness::new().await;
    let alice = SigningKey::from_bytes(&[1; 32]);
    let bob = SigningKey::from_bytes(&[2; 32]);
    let a = h.auth(&alice).await;
    let b = h.auth(&bob).await;
    h.publish(&bob, &b, 11).await;
    let input = h.submission(&alice, 10);
    assert_eq!(
        h.request("POST", "/v1/posts", Some(&a), input.clone())
            .await
            .0,
        StatusCode::OK
    );
    let confirm = format!(
        "/v1/posts/{}/confirm",
        input["commitment"].as_str().unwrap()
    );
    let blob = format!("/v1/blobs/{}", input["blobRef"].as_str().unwrap());
    let feed = format!("/v1/feed?day={DAY}");
    assert_eq!(
        h.request("POST", &confirm, Some(&a), json!({})).await.0,
        StatusCode::CONFLICT
    );
    assert_eq!(
        h.request("GET", &blob, Some(&b), json!(null)).await.0,
        StatusCode::NOT_FOUND
    );
    let (_, items) = h.request("GET", &feed, Some(&b), json!(null)).await;
    assert_eq!(items["items"].as_array().unwrap().len(), 1);
    h.onchain(&alice, &input);
    h.chain
        .checkins
        .lock()
        .unwrap()
        .get_mut(&(alice.verifying_key().to_bytes(), DAY))
        .unwrap()
        .blob_ref = [0; 32];
    assert_eq!(
        h.request("POST", &confirm, Some(&a), json!({})).await.0,
        StatusCode::CONFLICT
    );
    h.onchain(&alice, &input);
    assert_eq!(
        h.request("POST", &confirm, Some(&b), json!({})).await.0,
        StatusCode::NOT_FOUND
    );
    assert_eq!(
        h.request("POST", &confirm, Some(&a), json!({})).await.0,
        StatusCode::OK
    );
    h.chain
        .checkins
        .lock()
        .unwrap()
        .remove(&(alice.verifying_key().to_bytes(), DAY));
    let (_, items) = h.request("GET", &feed, Some(&b), json!(null)).await;
    assert_eq!(items["items"].as_array().unwrap().len(), 1);
    assert_eq!(
        h.request("GET", &blob, Some(&b), json!(null)).await.0,
        StatusCode::NOT_FOUND
    );
}

#[tokio::test]
async fn identical_retry_accepts_fresh_blockhash_but_second_post_conflicts() {
    let h = Harness::new().await;
    let user = SigningKey::from_bytes(&[1; 32]);
    let token = h.auth(&user).await;
    let mut input = h.submission(&user, 10);
    let (status, first) = h
        .request("POST", "/v1/posts", Some(&token), input.clone())
        .await;
    assert_eq!(status, StatusCode::OK);
    let (_, second) = h
        .request("POST", "/v1/posts", Some(&token), input.clone())
        .await;
    assert_eq!(first["transaction"], second["transaction"]);
    let mut raw = protocol::unbase64(input["transaction"].as_str().unwrap(), 1232).unwrap();
    raw[357..389].fill(2);
    input["transaction"] = json!(protocol::b64(&raw));
    let (status, fresh) = h.request("POST", "/v1/posts", Some(&token), input).await;
    assert_eq!(status, StatusCode::OK);
    assert_ne!(first["transaction"], fresh["transaction"]);
    assert_eq!(
        h.request("POST", "/v1/posts", Some(&token), h.submission(&user, 12))
            .await
            .0,
        StatusCode::CONFLICT
    );
}

#[tokio::test]
async fn tampered_key_hash_signature_transaction_and_day_are_refused_without_reservation() {
    let h = Harness::new().await;
    let user = SigningKey::from_bytes(&[1; 32]);
    let token = h.auth(&user).await;
    let original = h.submission(&user, 10);
    for field in ["postKey", "blobRef", "commitment", "transaction", "day"] {
        let mut input = original.clone();
        input[field] = match field {
            "postKey" => json!(protocol::b64(&[99; 32])),
            "blobRef" | "commitment" => json!(hex::encode([99; 32])),
            "transaction" => json!(protocol::b64(&[1; 32])),
            _ => json!(DAY - 1),
        };
        assert!(
            h.request("POST", "/v1/posts", Some(&token), input)
                .await
                .0
                .is_client_error(),
            "{field}"
        );
    }
    // Keep ciphertext/hash/transaction consistent but corrupt the detached manifest signature.
    let key = protocol::key64(original["postKey"].as_str().unwrap()).unwrap();
    let cipher =
        protocol::unbase64(original["blob"].as_str().unwrap(), protocol::MAX_BLOB).unwrap();
    let mut plain = protocol::open(&cipher, &key, protocol::PACKET_DOMAIN).unwrap();
    plain[protocol::PACKET_DOMAIN.len() + 1 + 4 + 175 + 4] ^= 1;
    let cipher = protocol::seal(&plain, &key, protocol::PACKET_DOMAIN).unwrap();
    let blob_ref = protocol::hash(&cipher);
    let commitment = protocol::hash_hex(original["commitment"].as_str().unwrap()).unwrap();
    let mut input = original.clone();
    input["blob"] = json!(protocol::b64(&cipher));
    input["blobRef"] = json!(hex::encode(blob_ref));
    input["transaction"] = json!(protocol::b64(&transaction(
        PROGRAM,
        user.verifying_key().to_bytes(),
        h.app.authority.verifying_key().to_bytes(),
        DAY,
        commitment,
        blob_ref,
        [1; 32]
    )));
    assert_eq!(
        h.request("POST", "/v1/posts", Some(&token), input).await.0,
        StatusCode::UNAUTHORIZED
    );
    assert!(h
        .app
        .db
        .get_post(original["commitment"].as_str().unwrap().into())
        .await
        .unwrap()
        .is_none());
    assert_eq!(
        h.request("POST", "/v1/posts", Some(&token), original)
            .await
            .0,
        StatusCode::OK
    );
}

#[tokio::test]
async fn moderation_rejection_does_not_reserve_day_or_sign() {
    let h = Harness::new().await;
    let user = SigningKey::from_bytes(&[1; 32]);
    let token = h.auth(&user).await;
    let input = h.submission(&user, 10);
    h.reviewer.0.store(false, Ordering::SeqCst);
    let (status, body) = h
        .request("POST", "/v1/posts", Some(&token), input.clone())
        .await;
    assert_eq!(status, StatusCode::UNPROCESSABLE_ENTITY);
    assert!(body.get("transaction").is_none());
    assert!(h
        .app
        .db
        .get_post(input["commitment"].as_str().unwrap().into())
        .await
        .unwrap()
        .is_none());
    h.reviewer.0.store(true, Ordering::SeqCst);
    assert_eq!(
        h.request("POST", "/v1/posts", Some(&token), h.submission(&user, 11))
            .await
            .0,
        StatusCode::OK
    );
}

#[tokio::test]
async fn decay_exit_rpc_outage_and_midnight_close_access() {
    let h = Harness::new().await;
    let user = SigningKey::from_bytes(&[1; 32]);
    let token = h.auth(&user).await;
    let post = h.publish(&user, &token, 10).await;
    let wallet = user.verifying_key().to_bytes();
    let feed = format!("/v1/feed?day={DAY}");
    {
        let mut profiles = h.chain.profiles.lock().unwrap();
        let p = profiles.get_mut(&wallet).unwrap();
        p.staked = 11;
        p.settled_day = DAY - 4;
    }
    assert_eq!(
        h.request("GET", &feed, Some(&token), json!(null)).await.0,
        StatusCode::FORBIDDEN
    );
    {
        let mut profiles = h.chain.profiles.lock().unwrap();
        let p = profiles.get_mut(&wallet).unwrap();
        p.staked = 50;
        p.settled_day = DAY;
        p.exit_unlock_at = h.clock.now();
    }
    assert_eq!(
        h.request("GET", &feed, Some(&token), json!(null)).await.0,
        StatusCode::FORBIDDEN
    );
    h.chain
        .profiles
        .lock()
        .unwrap()
        .get_mut(&wallet)
        .unwrap()
        .exit_unlock_at = 0;
    h.chain.available.store(false, Ordering::SeqCst);
    assert_eq!(
        h.request("GET", &feed, Some(&token), json!(null)).await.0,
        StatusCode::SERVICE_UNAVAILABLE
    );
    h.chain.available.store(true, Ordering::SeqCst);
    h.clock.0.store((DAY + 1) * 86400 + 100, Ordering::SeqCst);
    let token = h.auth(&user).await;
    assert_eq!(
        h.request("GET", &feed, Some(&token), json!(null)).await.0,
        StatusCode::FORBIDDEN
    );
    assert_eq!(
        h.request(
            "GET",
            &format!("/v1/blobs/{}", post["blobRef"].as_str().unwrap()),
            Some(&token),
            json!(null)
        )
        .await
        .0,
        StatusCode::FORBIDDEN
    );
}

#[tokio::test]
async fn session_replay_wrong_signer_unknown_expiry_and_token_expiry() {
    let h = Harness::new().await;
    let user = SigningKey::from_bytes(&[1; 32]);
    let wallet = protocol::address_string(&user.verifying_key().to_bytes());
    let (_, challenge) = h
        .request(
            "POST",
            "/v1/session/challenge",
            None,
            json!({"wallet":wallet}),
        )
        .await;
    let mut verify = json!({"wallet":wallet,"nonce":challenge["nonce"],"signature":protocol::b64(&SigningKey::from_bytes(&[2;32]).sign(challenge["message"].as_str().unwrap().as_bytes()).to_bytes())});
    assert_eq!(
        h.request("POST", "/v1/session/verify", None, verify.clone())
            .await
            .0,
        StatusCode::UNAUTHORIZED
    );
    verify["signature"] = json!(protocol::b64(
        &user
            .sign(challenge["message"].as_str().unwrap().as_bytes())
            .to_bytes()
    ));
    let (a, b) = tokio::join!(
        h.request("POST", "/v1/session/verify", None, verify.clone()),
        h.request("POST", "/v1/session/verify", None, verify.clone())
    );
    assert_eq!(
        [a.0, b.0].iter().filter(|&&s| s == StatusCode::OK).count(),
        1
    );
    let session = if a.0 == StatusCode::OK { a.1 } else { b.1 };
    let token = session["token"].as_str().unwrap();
    h.clock.0.fetch_add(30 * 86_400 + 1, Ordering::SeqCst);
    assert_eq!(
        h.request(
            "GET",
            &format!("/v1/feed?day={DAY}"),
            Some(token),
            json!(null)
        )
        .await
        .0,
        StatusCode::UNAUTHORIZED
    );
    let (_, challenge) = h
        .request(
            "POST",
            "/v1/session/challenge",
            None,
            json!({"wallet":wallet}),
        )
        .await;
    h.clock.0.fetch_add(301, Ordering::SeqCst);
    let mut request = json!({"wallet":wallet,"nonce":challenge["nonce"],"signature":protocol::b64(&user.sign(challenge["message"].as_str().unwrap().as_bytes()).to_bytes())});
    assert_eq!(
        h.request("POST", "/v1/session/verify", None, request.clone())
            .await
            .0,
        StatusCode::UNAUTHORIZED
    );
    request["nonce"] = json!(protocol::random_token());
    assert_eq!(
        h.request("POST", "/v1/session/verify", None, request)
            .await
            .0,
        StatusCode::UNAUTHORIZED
    );
}

#[tokio::test]
async fn expired_blockhash_never_reserves_a_post() {
    let h = Harness::new().await;
    let user = SigningKey::from_bytes(&[1; 32]);
    let token = h.auth(&user).await;
    let input = h.submission(&user, 10);
    h.chain.blockhash.store(false, Ordering::SeqCst);
    assert_eq!(
        h.request("POST", "/v1/posts", Some(&token), input.clone())
            .await
            .0,
        StatusCode::CONFLICT
    );
    assert!(h
        .app
        .db
        .get_post(input["commitment"].as_str().unwrap().into())
        .await
        .unwrap()
        .is_none());
}

#[tokio::test]
async fn kubernetes_probes_remain_available_when_api_rate_limit_is_reached() {
    let h = Harness::new().await;
    for _ in 0..120 {
        h.request("GET", "/not-found", None, json!(null)).await;
    }
    assert_eq!(
        h.request("GET", "/not-found", None, json!(null)).await.0,
        StatusCode::TOO_MANY_REQUESTS
    );
    assert_eq!(
        h.request("GET", "/healthz", None, json!(null)).await.0,
        StatusCode::OK
    );
    assert_eq!(
        h.request("GET", "/readyz", None, json!(null)).await.0,
        StatusCode::OK
    );
}

#[tokio::test]
async fn exit_unlocking_during_response_preparation_releases_nothing() {
    let h = Harness::new().await;
    let user = SigningKey::from_bytes(&[1; 32]);
    let token = h.auth(&user).await;
    let post = h.publish(&user, &token, 10).await;
    let start = h.clock.now();
    h.chain
        .profiles
        .lock()
        .unwrap()
        .get_mut(&user.verifying_key().to_bytes())
        .unwrap()
        .exit_unlock_at = start + 5;
    for path in [
        format!("/v1/feed?day={DAY}"),
        format!("/v1/blobs/{}", post["blobRef"].as_str().unwrap()),
    ] {
        h.clock.0.store(start, Ordering::SeqCst);
        // Initial reader check succeeds. The subsequent publisher check finishes at unlock.
        *h.chain.advance_after_reads.lock().unwrap() = Some((2, h.clock.clone(), start + 5));
        let (status, response) = h.request("GET", &path, Some(&token), json!(null)).await;
        assert_eq!(status, StatusCode::FORBIDDEN, "{path}: {response}");
        assert!(response.get("items").is_none());
        assert!(response.get("bytes").is_none());
    }
}

#[test]
fn packet_authentication_and_bounded_decoder_reject_truncation_trailing_bytes_and_wrong_domain() {
    let key = [8; 32];
    let mut bytes = protocol::PACKET_DOMAIN.to_vec();
    bytes.push(1);
    for field in [&[1u8; 175][..], &[2u8; 64], &[3u8; 7], &[4u8; 8]] {
        bytes.extend_from_slice(&(field.len() as u32).to_le_bytes());
        bytes.extend_from_slice(field);
    }
    let cipher = protocol::seal(&bytes, &key, protocol::PACKET_DOMAIN).unwrap();
    assert!(protocol::Packet::decrypt(&cipher, &key).is_ok());
    assert!(protocol::Packet::decrypt(&cipher, &[9; 32]).is_err());
    let mut corrupt = cipher.clone();
    *corrupt.last_mut().unwrap() ^= 1;
    assert!(protocol::Packet::decrypt(&corrupt, &key).is_err());
    for end in 0..bytes.len() {
        let cipher = protocol::seal(&bytes[..end], &key, protocol::PACKET_DOMAIN).unwrap();
        assert!(protocol::Packet::decrypt(&cipher, &key).is_err());
    }
    bytes.push(0);
    assert!(protocol::Packet::decrypt(
        &protocol::seal(&bytes, &key, protocol::PACKET_DOMAIN).unwrap(),
        &key
    )
    .is_err());
    bytes.pop();
    assert!(
        protocol::Packet::decrypt(&protocol::seal(&bytes, &key, b"different").unwrap(), &key)
            .is_err()
    );
}

#[tokio::test]
async fn proxy_clients_have_independent_quotas_and_spoofing_does_not_reset_them() {
    use axum::extract::ConnectInfo;
    use std::net::SocketAddr;
    let h = Harness::new().await;
    let router =
        api::router_with_trusted_proxies(h.app.clone(), vec!["10.1.0.0/16".parse().unwrap()]);
    async fn request(router: &Router, peer: &str, forwarded: &str) -> StatusCode {
        router
            .clone()
            .oneshot(
                Request::builder()
                    .uri("/not-found")
                    .extension(ConnectInfo(peer.parse::<SocketAddr>().unwrap()))
                    .header("x-forwarded-for", forwarded)
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap()
            .status()
    }
    for _ in 0..120 {
        assert_eq!(
            request(&router, "10.1.2.3:9000", "198.51.100.1").await,
            StatusCode::NOT_FOUND
        );
    }
    assert_eq!(
        request(&router, "10.1.2.3:9000", "192.0.2.99, 198.51.100.1").await,
        StatusCode::TOO_MANY_REQUESTS
    );
    assert_eq!(
        request(&router, "10.1.2.3:9000", "198.51.100.2").await,
        StatusCode::NOT_FOUND
    );
    for i in 0..120 {
        assert_eq!(
            request(&router, "203.0.113.1:9000", &format!("192.0.2.{i}")).await,
            StatusCode::NOT_FOUND
        );
    }
    assert_eq!(
        request(&router, "203.0.113.1:9000", "192.0.2.200").await,
        StatusCode::TOO_MANY_REQUESTS
    );
}

#[tokio::test]
async fn feed_parallel_reads_are_bounded_ordered_and_paginated() {
    let h = Harness::new().await;
    let mut token = String::new();
    let mut expected = Vec::new();
    for salt in 1..=20 {
        let user = SigningKey::from_bytes(&[salt + 40; 32]);
        token = h.auth(&user).await;
        let post = h.publish(&user, &token, salt).await;
        expected.push(post["commitment"].as_str().unwrap().to_owned());
    }
    expected.sort();
    h.chain.delayed_reads.store(true, Ordering::SeqCst);
    let (status, page) = h
        .request(
            "GET",
            &format!("/v1/feed?day={DAY}&limit=16"),
            Some(&token),
            json!(null),
        )
        .await;
    assert_eq!(status, StatusCode::OK, "{page}");
    let actual: Vec<_> = page["items"]
        .as_array()
        .unwrap()
        .iter()
        .map(|item| item["commitment"].as_str().unwrap())
        .collect();
    assert_eq!(actual, expected[..16]);
    assert_eq!(page["nextCursor"], expected[15]);
    assert_eq!(h.chain.peak_reads.load(Ordering::SeqCst), 8);
    assert_eq!(h.chain.active_reads.load(Ordering::SeqCst), 0);
    let (status, page) = h
        .request(
            "GET",
            &format!("/v1/feed?day={DAY}&limit=16&after={}", expected[15]),
            Some(&token),
            json!(null),
        )
        .await;
    assert_eq!(status, StatusCode::OK);
    let actual: Vec<_> = page["items"]
        .as_array()
        .unwrap()
        .iter()
        .map(|item| item["commitment"].as_str().unwrap())
        .collect();
    assert_eq!(actual, expected[16..]);
    assert!(page["nextCursor"].is_null());
    // A failure in a publisher read must return no partial keys and cancel work.
    let failing_wallet = h
        .chain
        .checkins
        .lock()
        .unwrap()
        .values()
        .find(|checkin| hex::encode(checkin.commitment) == expected[0])
        .unwrap()
        .owner;
    // Use another reader if the last published wallet happens to sort first.
    let reader = SigningKey::from_bytes(&[41; 32]);
    let alternate = h.auth(&reader).await;
    let reader = if reader.verifying_key().to_bytes() != failing_wallet {
        alternate
    } else {
        token
    };
    *h.chain.fail_read.lock().unwrap() = Some(failing_wallet);
    let (status, response) = h
        .request(
            "GET",
            &format!("/v1/feed?day={DAY}&limit=16"),
            Some(&reader),
            json!(null),
        )
        .await;
    assert_eq!(status, StatusCode::SERVICE_UNAVAILABLE);
    assert!(response.get("items").is_none());
    assert_eq!(h.chain.active_reads.load(Ordering::SeqCst), 0);
}
