use crate::{
    chain::{self, Chain, Expected},
    error::{Error, Result},
    moderation::Reviewer,
    protocol::{self, Key, Packet},
    store::{Blobs, Db, Post},
};
use axum::{
    extract::{ConnectInfo, DefaultBodyLimit, Path, Query, Request, State},
    http::{header, HeaderMap, StatusCode},
    middleware::{self, Next},
    response::{IntoResponse, Response},
    routing::{get, post, put},
    Json, Router,
};
use ed25519_dalek::SigningKey;
use futures_util::{stream, StreamExt, TryStreamExt};
use ipnet::IpNet;
use serde::{Deserialize, Serialize};
use serde_json::json;
use std::{
    collections::HashMap,
    net::{IpAddr, SocketAddr},
    sync::{Arc, Mutex},
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};
use tokio::sync::Semaphore;
use zeroize::{Zeroize, Zeroizing};

pub trait Clock: Send + Sync {
    fn now(&self) -> i64;
}
pub struct SystemClock;
impl Clock for SystemClock {
    fn now(&self) -> i64 {
        SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs() as i64
    }
}

pub struct App {
    pub db: Db,
    pub blobs: Arc<dyn Blobs>,
    pub chain: Arc<dyn Chain>,
    pub reviewer: Arc<dyn Reviewer>,
    pub clock: Arc<dyn Clock>,
    pub network: String,
    pub program: Key,
    pub origin: String,
    pub authority: SigningKey,
    pub inference_slots: Arc<Semaphore>,
}
impl App {
    fn day(&self) -> i64 {
        self.clock.now().div_euclid(86_400)
    }
    async fn wallet(&self, headers: &HeaderMap) -> Result<Key> {
        let token = headers
            .get(header::AUTHORIZATION)
            .and_then(|h| h.to_str().ok())
            .and_then(|v| v.strip_prefix("Bearer "))
            .filter(|v| v.len() == 43)
            .ok_or_else(Error::auth)?;
        protocol::address(&self.db.session(token.to_owned(), self.clock.now()).await?)
    }
    // A successful chain read grants access only until midnight or the known exit time.
    async fn reader(&self, wallet: Key, day: i64) -> Result<i64> {
        if day != self.day() {
            return Err(Error::forbidden());
        }
        let (config, profile, checkin) = tokio::try_join!(
            self.chain.config(),
            self.chain.profile(wallet),
            self.chain.check_in(wallet, day)
        )?;
        let profile = profile.ok_or_else(Error::forbidden)?;
        if !(profile.owner == wallet && profile.eligible(&config, self.clock.now()))
            || !checkin.is_some_and(|c| c.owner == wallet && c.day == day)
        {
            return Err(Error::forbidden());
        }
        let midnight = day.saturating_add(1).saturating_mul(86_400);
        Ok(if profile.exit_unlock_at > 0 {
            midnight.min(profile.exit_unlock_at)
        } else {
            midnight
        })
    }
    async fn matches_chain(&self, post: &Post) -> Result<bool> {
        let wallet = protocol::address(&post.wallet)?;
        Ok(self
            .chain
            .check_in(wallet, post.day)
            .await?
            .is_some_and(|c| {
                c.owner == wallet
                    && c.day == post.day
                    && hex::encode(c.commitment) == post.commitment
                    && hex::encode(c.blob_ref) == post.blob_ref
            }))
    }
}

// Walk from the TCP peer toward the client, stopping at the first untrusted hop.
// Never use client-controlled leftmost entries without checking the proxy chain.
fn client_ip(peer: IpAddr, headers: &HeaderMap, trusted: &[IpNet]) -> Result<IpAddr> {
    let peer = peer.to_canonical();
    let is_trusted = |ip: IpAddr| trusted.iter().any(|net| net.contains(&ip));
    if !is_trusted(peer) {
        return Ok(peer);
    }
    let invalid = || Error::bad("En-tête de proxy absent ou invalide.");
    let mut hops = Vec::new();
    for value in headers.get_all("x-forwarded-for") {
        for entry in value.to_str().map_err(|_| invalid())?.split(',') {
            if hops.len() == 32 {
                return Err(invalid());
            }
            hops.push(
                entry
                    .trim()
                    .parse::<IpAddr>()
                    .map_err(|_| invalid())?
                    .to_canonical(),
            );
        }
    }
    if hops.is_empty() {
        return Err(invalid());
    }
    let mut client = peer;
    for hop in hops.into_iter().rev() {
        if !is_trusted(client) {
            break;
        }
        client = hop;
    }
    Ok(client)
}

/// Bounded admission; forwarded IPs are accepted only from configured proxy networks.
#[derive(Clone)]
struct Admission {
    slots: Arc<Semaphore>,
    trusted_proxies: Arc<Vec<IpNet>>,
    peers: Arc<Mutex<HashMap<IpAddr, (Instant, u32)>>>,
}
async fn guard(State(admission): State<Admission>, request: Request, next: Next) -> Response {
    // Load shedding must not turn a healthy process into a Kubernetes restart loop.
    if matches!(request.uri().path(), "/healthz" | "/readyz") {
        let mut response = tokio::time::timeout(Duration::from_secs(3), next.run(request))
            .await
            .unwrap_or_else(|_| Error::unavailable().into_response());
        response
            .headers_mut()
            .insert(header::CACHE_CONTROL, "no-store, private".parse().unwrap());
        return response;
    }
    let result = async {
        let _permit = admission
            .slots
            .try_acquire()
            .map_err(|_| Error::unavailable())?;
        let peer = request
            .extensions()
            .get::<ConnectInfo<SocketAddr>>()
            .map(|c| c.0.ip())
            .unwrap_or(IpAddr::from([127, 0, 0, 1]));
        let peer = client_ip(peer, request.headers(), &admission.trusted_proxies)?;
        {
            let mut peers = admission.peers.lock().map_err(|_| Error::internal())?;
            let now = Instant::now();
            peers.retain(|_, (start, _)| now.duration_since(*start) < Duration::from_secs(60));
            if peers.len() >= 4096 && !peers.contains_key(&peer) {
                return Err(Error::unavailable());
            }
            let (_, count) = peers.entry(peer).or_insert((now, 0));
            *count += 1;
            if *count > 120 {
                return Err(Error(
                    StatusCode::TOO_MANY_REQUESTS,
                    "Trop de requêtes. Attendez une minute puis réessayez.",
                ));
            }
        }
        tokio::time::timeout(Duration::from_secs(45), next.run(request))
            .await
            .map_err(|_| Error::unavailable())
    }
    .await;
    let mut response = match result {
        Ok(r) => r,
        Err(e) => e.into_response(),
    };
    // Normalize framework rejections as well: no English parser internals in the API.
    if response.status().is_client_error()
        && response
            .headers()
            .get(header::CONTENT_TYPE)
            .is_none_or(|v| v != "application/json")
    {
        response = Error(
            response.status(),
            "Requête invalide ou trop volumineuse. Actualisez l’application puis réessayez.",
        )
        .into_response();
    }
    response
        .headers_mut()
        .insert(header::CACHE_CONTROL, "no-store, private".parse().unwrap());
    response.headers_mut().insert(
        header::HeaderName::from_static("x-content-type-options"),
        "nosniff".parse().unwrap(),
    );
    response
}

pub fn router(app: Arc<App>) -> Router {
    router_with_trusted_proxies(app, Vec::new())
}

pub fn router_with_trusted_proxies(app: Arc<App>, trusted_proxies: Vec<IpNet>) -> Router {
    Router::new()
        .route("/healthz", get(|| async { Json(json!({"status":"ok"})) }))
        .route("/readyz", get(readiness))
        .route("/v1/session/challenge", post(challenge))
        .route("/v1/session/verify", post(verify_session))
        .route("/v1/posts", post(submit))
        .route("/v1/posts/{commitment}/confirm", post(confirm))
        .route("/v1/posts/{commitment}/like", put(like).delete(unlike))
        .route("/v1/feed", get(feed))
        .route("/v1/blobs/{reference}", get(blob))
        .fallback(|| async { Error::missing() })
        .layer(DefaultBodyLimit::max(12 * 1024 * 1024))
        .layer(middleware::from_fn_with_state(
            Admission {
                slots: Arc::new(Semaphore::new(16)),
                trusted_proxies: Arc::new(trusted_proxies),
                peers: Arc::new(Mutex::new(HashMap::new())),
            },
            guard,
        ))
        .with_state(app)
}

async fn readiness(State(app): State<Arc<App>>) -> Result<Json<serde_json::Value>> {
    tokio::time::timeout(Duration::from_secs(2), app.db.health())
        .await
        .map_err(|_| Error::unavailable())??;
    Ok(Json(json!({"status":"ready"})))
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct ChallengeInput {
    wallet: String,
}
async fn challenge(
    State(app): State<Arc<App>>,
    Json(input): Json<ChallengeInput>,
) -> Result<Json<serde_json::Value>> {
    let wallet = protocol::address_string(&protocol::address(&input.wallet)?);
    let now = app.clock.now();
    let expires = now + 300;
    let nonce = protocol::random_token();
    let message = format!("Moment — connexion au serveur de clés\norigine: {}\nréseau: {}\nprogramme: {}\nwallet: {}\nnonce: {}\nexpire: {}\nCette signature ouvre une session de 15 minutes et n’autorise aucun transfert.",
        app.origin, app.network, protocol::address_string(&app.program), wallet, nonce, expires);
    app.db
        .challenge(wallet, message.clone(), nonce.clone(), expires, now)
        .await?;
    Ok(Json(
        json!({"nonce":nonce,"message":message,"expiresAt":expires}),
    ))
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct VerifyInput {
    wallet: String,
    nonce: String,
    signature: String,
}
const SESSION_TTL_SECONDS: i64 = 30 * 86_400;

async fn verify_session(
    State(app): State<Arc<App>>,
    Json(input): Json<VerifyInput>,
) -> Result<Json<serde_json::Value>> {
    if input.nonce.len() != 43 {
        return Err(Error::auth());
    }
    let wallet = protocol::address(&input.wallet)?;
    let wallet_string = protocol::address_string(&wallet);
    let now = app.clock.now();
    let message = app
        .db
        .nonce(wallet_string.clone(), input.nonce.clone(), now)
        .await?;
    protocol::verify(
        &wallet,
        message.as_bytes(),
        &protocol::unbase64(&input.signature, 64)?,
    )?;
    let token = protocol::random_token();
    // La session fait office de connexion côté app : trop courte, elle ferait
    // resigner dans le wallet à chaque ouverture.
    let expires = now + SESSION_TTL_SECONDS;
    app.db
        .consume_nonce(wallet_string, input.nonce, token.clone(), expires, now)
        .await?;
    Ok(Json(json!({"token":token,"expiresAt":expires})))
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct SubmitInput {
    day: i64,
    commitment: String,
    blob_ref: String,
    post_key: String,
    blob: String,
    transaction: String,
}
impl Drop for SubmitInput {
    fn drop(&mut self) {
        self.post_key.zeroize();
    }
}

async fn submit(
    State(app): State<Arc<App>>,
    headers: HeaderMap,
    Json(input): Json<SubmitInput>,
) -> Result<Json<serde_json::Value>> {
    let wallet = app.wallet(&headers).await?;
    if input.day != app.day() {
        return Err(Error::bad(
            "Le jour a changé. Reprenez la publication du jour.",
        ));
    }
    let commitment = protocol::hash_hex(&input.commitment)?;
    let blob_ref = protocol::hash_hex(&input.blob_ref)?;
    let key = Zeroizing::new(protocol::key64(&input.post_key)?);
    let ciphertext = protocol::unbase64(&input.blob, protocol::MAX_BLOB)?;
    if protocol::hash(&ciphertext) != blob_ref {
        return Err(Error::bad(
            "La référence du blob ne correspond pas. Reprenez la publication.",
        ));
    }
    let packet = Packet::decrypt(&ciphertext, &key)?;
    if packet.verify(&app.network, &app.program, &wallet, input.day)? != commitment {
        return Err(Error::bad(
            "Le commitment ne correspond pas. Reprenez la publication.",
        ));
    }
    let transaction = protocol::unbase64(&input.transaction, 1232)?;
    let expected = Expected {
        program: app.program,
        wallet,
        authority: app.authority.verifying_key().to_bytes(),
        day: input.day,
        commitment,
        blob_ref,
    };
    let blockhash = chain::validate_transaction(&transaction, &expected)?;
    let (config, profile, valid) = tokio::try_join!(
        app.chain.config(),
        app.chain.profile(wallet),
        app.chain.blockhash_valid(blockhash)
    )?;
    if config.authority != expected.authority {
        return Err(Error::unavailable());
    }
    if !profile.is_some_and(|p| p.owner == wallet && p.eligible(&config, app.clock.now())) {
        return Err(Error::forbidden());
    }
    if !valid {
        return Err(Error(StatusCode::CONFLICT, "Transaction expirée. Reconstruisez-la avec un blockhash récent puis renvoyez le même paquet."));
    }
    let slot = app
        .inference_slots
        .clone()
        .try_acquire_owned()
        .map_err(|_| Error::unavailable())?;
    let reviewer = app.reviewer.clone();
    tokio::task::spawn_blocking(move || {
        let _slot = slot;
        reviewer.review(&packet.rear, &packet.front)
    })
    .await
    .map_err(|_| Error::unavailable())??;
    // Check again after native inference: do not authorize yesterday's packet near midnight.
    if input.day != app.day() {
        return Err(Error::bad(
            "Le jour a changé. Reprenez la publication du jour.",
        ));
    }
    let record = Post {
        commitment: input.commitment.clone(),
        wallet: protocol::address_string(&wallet),
        day: input.day,
        blob_ref: input.blob_ref.clone(),
        published: false,
    };
    app.db.reserve(record, *key, app.clock.now()).await?;
    app.blobs.put(blob_ref, ciphertext).await?;
    let signed = chain::cosign(&transaction, &expected, &app.authority)?;
    Ok(Json(
        json!({"transaction":protocol::b64(&signed),"commitment":input.commitment,"blobRef":input.blob_ref,"state":"authorized"}),
    ))
}

async fn confirm(
    State(app): State<Arc<App>>,
    headers: HeaderMap,
    Path(commitment): Path<String>,
) -> Result<Json<serde_json::Value>> {
    let wallet = app.wallet(&headers).await?;
    protocol::hash_hex(&commitment)?;
    let record = app
        .db
        .get_post(commitment.clone())
        .await?
        .filter(|p| p.wallet == protocol::address_string(&wallet))
        .ok_or_else(Error::missing)?;
    if !app.matches_chain(&record).await? {
        return Err(Error(
            StatusCode::CONFLICT,
            "Le Moment n’est pas confirmé sur Solana. Attendez puis réessayez.",
        ));
    }
    // Also verify the object before opening access (retrying an interrupted upload is safe).
    app.blobs.get(protocol::hash_hex(&record.blob_ref)?).await?;
    app.db.mark_published(commitment).await?;
    Ok(Json(json!({"state":"published"})))
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct FeedQuery {
    day: i64,
    after: Option<String>,
    limit: Option<usize>,
}
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct FeedItem {
    wallet: String,
    commitment: String,
    blob_ref: String,
    post_key: String,
    blob_url: String,
    likes: i64,
    liked: bool,
}
impl Drop for FeedItem {
    fn drop(&mut self) {
        self.post_key.zeroize();
    }
}
async fn feed(
    State(app): State<Arc<App>>,
    headers: HeaderMap,
    Query(query): Query<FeedQuery>,
) -> Result<Json<serde_json::Value>> {
    let wallet = app.wallet(&headers).await?;
    let after = query.after.unwrap_or_default();
    if !after.is_empty() {
        protocol::hash_hex(&after)?;
    }
    let limit = query.limit.unwrap_or(20);
    if !(1..=50).contains(&limit) {
        return Err(Error::bad(
            "La taille de page doit être comprise entre 1 et 50.",
        ));
    }
    let read_until = app.reader(wallet, query.day).await?;
    let records = app.db.feed(query.day, after, limit + 1).await?;
    let more = records.len() > limit;
    let records: Vec<_> = records.into_iter().take(limit).collect();
    let cursor = records.last().map(|record| record.commitment.clone());
    // Ordered, bounded futures: preserve pagination and cancel outstanding reads on
    // error/timeout. No detached tasks may release keys after the request ends.
    let items: Vec<Option<FeedItem>> = stream::iter(records)
        .map(|record| {
            let app = &app;
            async move {
                if !app.matches_chain(&record).await? {
                    return Ok::<_, Error>(None);
                }
                let key = app.db.post_key(record.clone()).await?;
                Ok(Some(FeedItem {
                    wallet: record.wallet,
                    commitment: record.commitment,
                    blob_url: format!("/v1/blobs/{}", record.blob_ref),
                    blob_ref: record.blob_ref,
                    post_key: protocol::b64(&key),
                    likes: 0,
                    liked: false,
                }))
            }
        })
        .buffered(8)
        .try_collect()
        .await?;
    let mut items: Vec<_> = items.into_iter().flatten().collect();
    let likes = app
        .db
        .likes(
            items.iter().map(|i| i.commitment.clone()).collect(),
            protocol::address_string(&wallet),
        )
        .await?;
    for item in &mut items {
        if let Some(&(count, mine)) = likes.get(&item.commitment) {
            item.likes = count;
            item.liked = mine;
        }
    }
    if query.day != app.day() || app.clock.now() >= read_until {
        return Err(Error::forbidden());
    }
    Ok(Json(
        json!({"items":items,"nextCursor":if more {cursor} else {None}}),
    ))
}

async fn like(
    State(app): State<Arc<App>>,
    headers: HeaderMap,
    Path(commitment): Path<String>,
) -> Result<Json<serde_json::Value>> {
    set_like(app, headers, commitment, true).await
}
async fn unlike(
    State(app): State<Arc<App>>,
    headers: HeaderMap,
    Path(commitment): Path<String>,
) -> Result<Json<serde_json::Value>> {
    set_like(app, headers, commitment, false).await
}
/// Same access rule as the feed: only today's members may like today's posts.
async fn set_like(
    app: Arc<App>,
    headers: HeaderMap,
    commitment: String,
    liked: bool,
) -> Result<Json<serde_json::Value>> {
    let wallet = app.wallet(&headers).await?;
    protocol::hash_hex(&commitment)?;
    let post = app
        .db
        .get_post(commitment.clone())
        .await?
        .filter(|p| p.published && p.day == app.day())
        .ok_or_else(Error::missing)?;
    app.reader(wallet, post.day).await?;
    if !app.matches_chain(&post).await? {
        return Err(Error::missing());
    }
    let me = protocol::address_string(&wallet);
    app.db
        .set_like(commitment.clone(), me.clone(), liked, app.clock.now())
        .await?;
    let (likes, liked) = app
        .db
        .likes(vec![commitment.clone()], me)
        .await?
        .get(&commitment)
        .copied()
        .unwrap_or((0, false));
    Ok(Json(json!({"likes": likes, "liked": liked})))
}

async fn blob(
    State(app): State<Arc<App>>,
    headers: HeaderMap,
    Path(reference): Path<String>,
) -> Result<Response> {
    let wallet = app.wallet(&headers).await?;
    let hash = protocol::hash_hex(&reference)?;
    let record = app
        .db
        .get_blob_post(reference)
        .await?
        .filter(|p| p.published)
        .ok_or_else(Error::missing)?;
    let read_until = app.reader(wallet, record.day).await?;
    if !app.matches_chain(&record).await? {
        return Err(Error::missing());
    }
    let bytes = app.blobs.get(hash).await?;
    if record.day != app.day() || app.clock.now() >= read_until {
        return Err(Error::forbidden());
    }
    Ok(([(header::CONTENT_TYPE, "application/octet-stream")], bytes).into_response())
}

#[cfg(test)]
mod proxy_tests {
    use super::*;

    #[test]
    fn forwarded_ips_require_trusted_peers_and_walk_from_the_right() {
        let trusted = vec![
            "10.1.0.0/16".parse().unwrap(),
            "fd00:1::/64".parse().unwrap(),
        ];
        let mut headers = HeaderMap::new();
        headers.append(
            "x-forwarded-for",
            "192.0.2.99, 198.51.100.7".parse().unwrap(),
        );
        headers.append("x-forwarded-for", "10.1.2.3".parse().unwrap());
        assert_eq!(
            client_ip("10.1.2.4".parse().unwrap(), &headers, &trusted).unwrap(),
            "198.51.100.7".parse::<IpAddr>().unwrap()
        );
        // An untrusted peer cannot replace its quota identity, even with garbage.
        headers.insert("x-forwarded-for", "forged".parse().unwrap());
        assert_eq!(
            client_ip("203.0.113.1".parse().unwrap(), &headers, &trusted).unwrap(),
            "203.0.113.1".parse::<IpAddr>().unwrap()
        );
        assert!(client_ip("10.1.2.4".parse().unwrap(), &headers, &trusted).is_err());
        headers.insert("x-forwarded-for", "2001:db8::1, fd00:1::2".parse().unwrap());
        assert_eq!(
            client_ip("fd00:1::3".parse().unwrap(), &headers, &trusted).unwrap(),
            "2001:db8::1".parse::<IpAddr>().unwrap()
        );
        headers.insert("x-forwarded-for", "::ffff:198.51.100.7".parse().unwrap());
        assert_eq!(
            client_ip("::ffff:10.1.2.4".parse().unwrap(), &headers, &trusted).unwrap(),
            "198.51.100.7".parse::<IpAddr>().unwrap()
        );
    }

    #[test]
    fn trusted_proxies_must_supply_a_bounded_valid_chain() {
        let trusted = vec!["10.1.0.0/16".parse().unwrap()];
        let peer = "10.1.2.4".parse().unwrap();
        assert!(client_ip(peer, &HeaderMap::new(), &trusted).is_err());
        for value in [
            "",
            "unknown",
            "1.2.3.4,",
            "1.2.3.4:80",
            "[::1]",
            &vec!["1.2.3.4"; 33].join(","),
        ] {
            let mut headers = HeaderMap::new();
            headers.insert("x-forwarded-for", value.parse().unwrap());
            assert!(client_ip(peer, &headers, &trusted).is_err(), "{value}");
        }
    }
}
