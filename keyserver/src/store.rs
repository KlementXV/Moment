//! Private content-addressed storage and encrypted key custody.
use crate::{
    error::{Error, Result},
    protocol::{self, Key, MAX_BLOB},
};
use async_trait::async_trait;
use object_store::{
    aws::{AmazonS3Builder, S3ConditionalPut},
    local::LocalFileSystem,
    path::Path,
    ClientOptions, ObjectStore, PutMode, PutOptions, RetryConfig,
};
use sqlx::{
    postgres::{PgConnectOptions, PgPoolOptions, PgRow},
    ConnectOptions, PgPool, Row,
};
use std::{sync::Arc, time::Duration};
use zeroize::Zeroizing;

static MIGRATOR: sqlx::migrate::Migrator = sqlx::migrate!("./migrations");
// Serializes admission across independent pools/pods. The transaction releases it automatically.
const CHALLENGE_ADMISSION_LOCK: i64 = 0x4d4f_4d45_4e54_0001;

#[derive(Clone, Debug)]
pub struct Post {
    pub commitment: String,
    pub wallet: String,
    pub day: i64,
    pub blob_ref: String,
    pub published: bool,
}
fn row(r: PgRow) -> Result<Post> {
    Ok(Post {
        commitment: r.try_get("commitment").map_err(db_error)?,
        wallet: r.try_get("wallet").map_err(db_error)?,
        day: r.try_get("day").map_err(db_error)?,
        blob_ref: r.try_get("blob_ref").map_err(db_error)?,
        published: r.try_get("published").map_err(db_error)?,
    })
}
fn aad(p: &Post) -> Vec<u8> {
    serde_json::to_vec(&(
        "moment-key-v1",
        &p.commitment,
        &p.wallet,
        p.day,
        &p.blob_ref,
    ))
    .expect("strings serialize")
}
fn db_error(_: sqlx::Error) -> Error {
    Error::unavailable()
}
#[derive(Clone)]
pub struct Db {
    pool: PgPool,
    master: Arc<Zeroizing<Key>>,
}
impl Db {
    /// The URL supports sslmode=verify-full and sslrootcert for CNPG's server CA.
    pub async fn open(url: &str, master_key: Key) -> Result<Self> {
        let options = url.parse::<PgConnectOptions>().map_err(db_error)?;
        Self::connect(options, 10, master_key).await
    }
    pub async fn connect(
        options: PgConnectOptions,
        max_connections: u32,
        master_key: Key,
    ) -> Result<Self> {
        if max_connections == 0 {
            return Err(Error::internal());
        }
        let master = Arc::new(Zeroizing::new(master_key));
        let pool = PgPoolOptions::new()
            .max_connections(max_connections)
            .acquire_timeout(Duration::from_secs(10))
            .after_connect(|connection, _| {
                Box::pin(async move {
                    // Bound waits on failed or contending peers even when a request is cancelled.
                    sqlx::query("SET statement_timeout = '15s'")
                        .execute(&mut *connection)
                        .await?;
                    sqlx::query("SET lock_timeout = '10s'")
                        .execute(&mut *connection)
                        .await?;
                    sqlx::query("SET idle_in_transaction_session_timeout = '15s'")
                        .execute(&mut *connection)
                        .await?;
                    Ok(())
                })
            })
            .connect_with(
                options
                    .application_name("moment-keyserver")
                    .disable_statement_logging(),
            )
            .await
            .map_err(db_error)?;
        // SQLx takes PostgreSQL's advisory migration lock before checking/applying migrations.
        if MIGRATOR.run(&pool).await.is_err() {
            pool.close().await;
            return Err(Error::internal());
        }
        let db = Self { pool, master };
        if let Err(error) = db.verify_master().await {
            db.pool.close().await;
            return Err(error);
        }
        Ok(db)
    }
    async fn verify_master(&self) -> Result<()> {
        let proposed = protocol::seal(b"moment-keyserver", &self.master, b"moment-master-key-v1")?;
        let mut tx = self.pool.begin().await.map_err(db_error)?;
        // Concurrent startup may propose different ciphertexts. Only the persisted winner matters.
        sqlx::query("INSERT INTO metadata(id,marker) VALUES(1,$1) ON CONFLICT (id) DO NOTHING")
            .bind(proposed)
            .execute(&mut *tx)
            .await
            .map_err(db_error)?;
        let marker: Vec<u8> = sqlx::query_scalar("SELECT marker FROM metadata WHERE id=1")
            .fetch_one(&mut *tx)
            .await
            .map_err(db_error)?;
        let plain = protocol::open(&marker, &self.master, b"moment-master-key-v1")
            .map_err(|_| Error::internal())?;
        if plain.as_slice() != b"moment-keyserver" {
            return Err(Error::internal());
        }
        tx.commit().await.map_err(db_error)
    }
    pub async fn health(&self) -> Result<()> {
        sqlx::query("SELECT 1")
            .execute(&self.pool)
            .await
            .map_err(db_error)?;
        Ok(())
    }
    pub async fn challenge(
        &self,
        wallet: String,
        message: String,
        nonce: String,
        expires: i64,
        now: i64,
    ) -> Result<()> {
        let mut tx = self.pool.begin().await.map_err(db_error)?;
        sqlx::query("SELECT pg_advisory_xact_lock($1)")
            .bind(CHALLENGE_ADMISSION_LOCK)
            .execute(&mut *tx)
            .await
            .map_err(db_error)?;
        sqlx::query("DELETE FROM challenges WHERE expires<=$1 OR consumed")
            .bind(now)
            .execute(&mut *tx)
            .await
            .map_err(db_error)?;
        sqlx::query("DELETE FROM sessions WHERE expires<=$1")
            .bind(now)
            .execute(&mut *tx)
            .await
            .map_err(db_error)?;
        let count: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM challenges")
            .fetch_one(&mut *tx)
            .await
            .map_err(db_error)?;
        if count >= 10_000 {
            return Err(Error(
                axum::http::StatusCode::TOO_MANY_REQUESTS,
                "Trop de connexions en attente. Réessayez dans quelques minutes.",
            ));
        }
        sqlx::query("INSERT INTO challenges(nonce,wallet,message,expires) VALUES($1,$2,$3,$4)")
            .bind(nonce)
            .bind(wallet)
            .bind(message)
            .bind(expires)
            .execute(&mut *tx)
            .await
            .map_err(db_error)?;
        tx.commit().await.map_err(db_error)
    }
    pub async fn nonce(&self, wallet: String, nonce: String, now: i64) -> Result<String> {
        sqlx::query_scalar(
            "SELECT message FROM challenges
            WHERE wallet=$1 AND nonce=$2 AND expires>$3 AND NOT consumed",
        )
        .bind(wallet)
        .bind(nonce)
        .bind(now)
        .fetch_optional(&self.pool)
        .await
        .map_err(db_error)?
        .ok_or_else(Error::auth)
    }
    pub async fn consume_nonce(
        &self,
        wallet: String,
        nonce: String,
        token: String,
        expires: i64,
        now: i64,
    ) -> Result<()> {
        let token = Zeroizing::new(token);
        let token_hash = protocol::hash(token.as_bytes());
        drop(token);
        let mut tx = self.pool.begin().await.map_err(db_error)?;
        let consumed = sqlx::query(
            "UPDATE challenges SET consumed=TRUE
            WHERE wallet=$1 AND nonce=$2 AND expires>$3 AND NOT consumed",
        )
        .bind(&wallet)
        .bind(nonce)
        .bind(now)
        .execute(&mut *tx)
        .await
        .map_err(db_error)?;
        if consumed.rows_affected() != 1 {
            return Err(Error::auth());
        }
        sqlx::query("INSERT INTO sessions(token_hash,wallet,expires) VALUES($1,$2,$3)")
            .bind(token_hash.as_slice())
            .bind(wallet)
            .bind(expires)
            .execute(&mut *tx)
            .await
            .map_err(db_error)?;
        tx.commit().await.map_err(db_error)
    }
    pub async fn session(&self, token: String, now: i64) -> Result<String> {
        let token = Zeroizing::new(token);
        let hash = protocol::hash(token.as_bytes());
        drop(token);
        sqlx::query_scalar("SELECT wallet FROM sessions WHERE token_hash=$1 AND expires>$2")
            .bind(hash.as_slice())
            .bind(now)
            .fetch_optional(&self.pool)
            .await
            .map_err(db_error)?
            .ok_or_else(Error::auth)
    }
    pub async fn reserve(&self, post: Post, key: Key, now: i64) -> Result<()> {
        let key = Zeroizing::new(key);
        let wrapped = protocol::seal(key.as_slice(), &self.master, &aad(&post))?;
        let mut tx = self.pool.begin().await.map_err(db_error)?;
        // Unique constraints serialize conflicting reservations across pods. A later SELECT gets
        // a fresh READ COMMITTED snapshot after any competing INSERT has committed.
        sqlx::query(
            "INSERT INTO posts(commitment,wallet,day,blob_ref,wrapped_key,created)
            VALUES($1,$2,$3,$4,$5,$6) ON CONFLICT DO NOTHING",
        )
        .bind(&post.commitment)
        .bind(&post.wallet)
        .bind(post.day)
        .bind(&post.blob_ref)
        .bind(wrapped)
        .bind(now)
        .execute(&mut *tx)
        .await
        .map_err(db_error)?;
        let existing = sqlx::query(
            "SELECT commitment,wallet,day,blob_ref,published,wrapped_key FROM posts
            WHERE (wallet=$1 AND day=$2) OR commitment=$3 OR blob_ref=$4 LIMIT 1",
        )
        .bind(&post.wallet)
        .bind(post.day)
        .bind(&post.commitment)
        .bind(&post.blob_ref)
        .fetch_one(&mut *tx)
        .await
        .map_err(db_error)?;
        let wrapped: Vec<u8> = existing.try_get("wrapped_key").map_err(db_error)?;
        let previous = row(existing)?;
        if previous.commitment != post.commitment
            || previous.wallet != post.wallet
            || previous.day != post.day
            || previous.blob_ref != post.blob_ref
        {
            return Err(Error::conflict());
        }
        let old_key = protocol::open(&wrapped, &self.master, &aad(&previous))
            .map_err(|_| Error::internal())?;
        if old_key.as_slice() != key.as_slice() {
            return Err(Error::conflict());
        }
        tx.commit().await.map_err(db_error)
    }
    pub async fn get_post(&self, commitment: String) -> Result<Option<Post>> {
        sqlx::query(
            "SELECT commitment,wallet,day,blob_ref,published FROM posts WHERE commitment=$1",
        )
        .bind(commitment)
        .fetch_optional(&self.pool)
        .await
        .map_err(db_error)?
        .map(row)
        .transpose()
    }
    pub async fn get_blob_post(&self, blob_ref: String) -> Result<Option<Post>> {
        sqlx::query("SELECT commitment,wallet,day,blob_ref,published FROM posts WHERE blob_ref=$1")
            .bind(blob_ref)
            .fetch_optional(&self.pool)
            .await
            .map_err(db_error)?
            .map(row)
            .transpose()
    }
    pub async fn mark_published(&self, commitment: String) -> Result<()> {
        let changed = sqlx::query("UPDATE posts SET published=TRUE WHERE commitment=$1")
            .bind(commitment)
            .execute(&self.pool)
            .await
            .map_err(db_error)?;
        if changed.rows_affected() != 1 {
            return Err(Error::missing());
        }
        Ok(())
    }
    pub async fn feed(&self, day: i64, after: String, limit: usize) -> Result<Vec<Post>> {
        sqlx::query(
            "SELECT commitment,wallet,day,blob_ref,published FROM posts
            WHERE day=$1 AND published AND commitment>$2 ORDER BY commitment LIMIT $3",
        )
        .bind(day)
        .bind(after)
        .bind(limit.min(100) as i64)
        .fetch_all(&self.pool)
        .await
        .map_err(db_error)?
        .into_iter()
        .map(row)
        .collect()
    }
    pub async fn set_like(
        &self,
        commitment: String,
        wallet: String,
        liked: bool,
        now: i64,
    ) -> Result<()> {
        let query = if liked {
            sqlx::query("INSERT INTO likes (commitment,wallet,created) VALUES ($1,$2,$3) ON CONFLICT DO NOTHING")
                .bind(commitment)
                .bind(wallet)
                .bind(now)
        } else {
            sqlx::query("DELETE FROM likes WHERE commitment=$1 AND wallet=$2")
                .bind(commitment)
                .bind(wallet)
        };
        query.execute(&self.pool).await.map_err(db_error)?;
        Ok(())
    }
    /// Like count and whether `wallet` liked, for each requested post.
    pub async fn likes(
        &self,
        commitments: Vec<String>,
        wallet: String,
    ) -> Result<std::collections::HashMap<String, (i64, bool)>> {
        sqlx::query(
            "SELECT commitment, COUNT(*) AS n, BOOL_OR(wallet=$2) AS mine FROM likes
            WHERE commitment = ANY($1) GROUP BY commitment",
        )
        .bind(commitments)
        .bind(wallet)
        .fetch_all(&self.pool)
        .await
        .map_err(db_error)?
        .into_iter()
        .map(|r| {
            Ok((
                r.try_get("commitment").map_err(db_error)?,
                (
                    r.try_get("n").map_err(db_error)?,
                    r.try_get("mine").map_err(db_error)?,
                ),
            ))
        })
        .collect()
    }
    pub async fn post_key(&self, post: Post) -> Result<Zeroizing<Vec<u8>>> {
        let wrapped: Vec<u8> =
            sqlx::query_scalar("SELECT wrapped_key FROM posts WHERE commitment=$1")
                .bind(&post.commitment)
                .fetch_optional(&self.pool)
                .await
                .map_err(db_error)?
                .ok_or_else(Error::missing)?;
        protocol::open(&wrapped, &self.master, &aad(&post)).map_err(|_| Error::internal())
    }
}

#[async_trait]
pub trait Blobs: Send + Sync {
    async fn put(&self, reference: Key, bytes: Vec<u8>) -> Result<()>;
    async fn get(&self, reference: Key) -> Result<Vec<u8>>;
}
pub struct ObjectBlobs {
    store: Arc<dyn ObjectStore>,
}
impl ObjectBlobs {
    pub fn local(path: &str) -> anyhow::Result<Self> {
        std::fs::create_dir_all(path)?;
        Ok(Self {
            store: Arc::new(LocalFileSystem::new_with_prefix(path)?),
        })
    }
    pub fn r2(endpoint: &str, bucket: &str, access: &str, secret: &str) -> anyhow::Result<Self> {
        anyhow::ensure!(
            endpoint.starts_with("https://"),
            "Le stockage R2 exige HTTPS."
        );
        let store = Self::r2_builder(endpoint, bucket, access, secret)
            .build()
            .map_err(|_| anyhow::anyhow!("Configuration R2 invalide."))?;
        Ok(Self {
            store: Arc::new(store),
        })
    }
    fn r2_builder(endpoint: &str, bucket: &str, access: &str, secret: &str) -> AmazonS3Builder {
        AmazonS3Builder::new()
            .with_endpoint(endpoint)
            .with_bucket_name(bucket)
            .with_access_key_id(access)
            .with_secret_access_key(secret)
            .with_region("auto")
            .with_virtual_hosted_style_request(false)
            .with_client_options(
                ClientOptions::new()
                    .with_allow_http(false)
                    .with_connect_timeout(Duration::from_secs(3))
                    .with_timeout(Duration::from_secs(10)),
            )
            .with_retry(RetryConfig {
                max_retries: 1,
                retry_timeout: Duration::from_secs(12),
                ..Default::default()
            })
            .with_conditional_put(S3ConditionalPut::ETagMatch)
    }
    fn path(reference: Key) -> Path {
        Path::from(format!("moments/{}", hex::encode(reference)))
    }
}
#[async_trait]
impl Blobs for ObjectBlobs {
    async fn put(&self, reference: Key, bytes: Vec<u8>) -> Result<()> {
        if bytes.len() > MAX_BLOB || protocol::hash(&bytes) != reference {
            return Err(Error::bad(
                "Paquet chiffré invalide. Reprenez la publication.",
            ));
        }
        match self
            .store
            .put_opts(
                &Self::path(reference),
                bytes.into(),
                PutOptions {
                    mode: PutMode::Create,
                    ..Default::default()
                },
            )
            .await
        {
            Ok(_) => Ok(()),
            Err(object_store::Error::AlreadyExists { .. })
            | Err(object_store::Error::Precondition { .. }) => {
                self.get(reference).await?;
                Ok(())
            }
            Err(_) => Err(Error::unavailable()),
        }
    }
    async fn get(&self, reference: Key) -> Result<Vec<u8>> {
        let path = Self::path(reference);
        let result = self
            .store
            .get_opts(
                &path,
                object_store::GetOptions {
                    range: Some((0..(MAX_BLOB as u64 + 1)).into()),
                    ..Default::default()
                },
            )
            .await
            .map_err(|e| match e {
                object_store::Error::NotFound { .. } => Error::missing(),
                _ => Error::unavailable(),
            })?;
        if result.meta.size > MAX_BLOB as u64 {
            return Err(Error::internal());
        }
        let bytes = result.bytes().await.map_err(|_| Error::unavailable())?;
        if bytes.len() > MAX_BLOB || protocol::hash(&bytes) != reference {
            return Err(Error::internal());
        }
        Ok(bytes.to_vec())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Mutex;

    struct TestDatabase {
        admin: PgPool,
        options: PgConnectOptions,
        schema: String,
    }
    impl TestDatabase {
        async fn create() -> Self {
            let url = std::env::var("TEST_DATABASE_URL").expect(
                "TEST_DATABASE_URL is required; start the disposable PostgreSQL test service",
            );
            let options: PgConnectOptions = url.parse().expect("valid TEST_DATABASE_URL");
            let admin = PgPoolOptions::new()
                .max_connections(1)
                .connect_with(options.clone())
                .await
                .expect("PostgreSQL test connection");
            let schema = format!("store_test_{}", hex::encode(rand::random::<[u8; 16]>()));
            sqlx::query(&format!("CREATE SCHEMA {schema}"))
                .execute(&admin)
                .await
                .unwrap();
            let options = options.options([("search_path", schema.as_str())]);
            Self {
                admin,
                options,
                schema,
            }
        }
        async fn connect(&self, key: Key) -> Db {
            Db::connect(self.options.clone(), 3, key).await.unwrap()
        }
        async fn cleanup(self, pools: &[&Db]) {
            for db in pools {
                db.pool.close().await;
            }
            sqlx::query(&format!("DROP SCHEMA {} CASCADE", self.schema))
                .execute(&self.admin)
                .await
                .unwrap();
            self.admin.close().await;
        }
    }
    fn post(id: &str, wallet: &str) -> Post {
        Post {
            commitment: id.into(),
            wallet: wallet.into(),
            day: 10,
            blob_ref: format!("blob-{id}"),
            published: false,
        }
    }
    #[tokio::test]
    async fn migrations_and_master_initialization_are_safe_across_pools() {
        let test = TestDatabase::create().await;
        // Both independent pools apply migrations and initialize the same marker concurrently.
        let (first, second) = tokio::join!(test.connect([7; 32]), test.connect([7; 32]));
        first.health().await.unwrap();
        second.health().await.unwrap();
        let count: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM _sqlx_migrations WHERE success")
            .fetch_one(&first.pool)
            .await
            .unwrap();
        assert_eq!(count as usize, MIGRATOR.iter().count());
        let count: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM metadata")
            .fetch_one(&first.pool)
            .await
            .unwrap();
        assert_eq!(count, 1);
        assert!(Db::connect(test.options.clone(), 2, [8; 32]).await.is_err());
        test.cleanup(&[&first, &second]).await;
    }
    #[tokio::test]
    async fn nonce_consumption_is_atomic_across_pools_and_expiring() {
        let test = TestDatabase::create().await;
        let first = test.connect([7; 32]).await;
        let second = test.connect([7; 32]).await;
        first
            .challenge("w".into(), "message".into(), "n".into(), 20, 10)
            .await
            .unwrap();
        assert_eq!(
            second.nonce("w".into(), "n".into(), 11).await.unwrap(),
            "message"
        );
        let (a, b) = tokio::join!(
            first.consume_nonce("w".into(), "n".into(), "a".into(), 30, 11),
            second.consume_nonce("w".into(), "n".into(), "b".into(), 30, 11),
        );
        assert_ne!(a.is_ok(), b.is_ok());
        let token = if a.is_ok() { "a" } else { "b" };
        assert_eq!(first.session(token.into(), 29).await.unwrap(), "w");
        assert!(second.session(token.into(), 30).await.is_err());
        assert!(first.nonce("w".into(), "n".into(), 12).await.is_err());
        let hash: Vec<u8> = sqlx::query_scalar("SELECT token_hash FROM sessions")
            .fetch_one(&first.pool)
            .await
            .unwrap();
        assert_eq!(hash, protocol::hash(token.as_bytes()));
        test.cleanup(&[&first, &second]).await;
    }
    #[tokio::test]
    async fn failed_session_insert_rolls_back_nonce_consumption() {
        let test = TestDatabase::create().await;
        let db = test.connect([7; 32]).await;
        for nonce in ["n1", "n2"] {
            db.challenge("w".into(), "m".into(), nonce.into(), 20, 10)
                .await
                .unwrap();
        }
        db.consume_nonce("w".into(), "n1".into(), "token".into(), 30, 11)
            .await
            .unwrap();
        assert!(db
            .consume_nonce("w".into(), "n2".into(), "token".into(), 30, 11)
            .await
            .is_err());
        assert_eq!(db.nonce("w".into(), "n2".into(), 11).await.unwrap(), "m");
        db.consume_nonce("w".into(), "n2".into(), "other-token".into(), 30, 11)
            .await
            .unwrap();
        test.cleanup(&[&db]).await;
    }
    #[tokio::test]
    async fn pending_challenges_do_not_lock_out_a_target_wallet() {
        let test = TestDatabase::create().await;
        let db = test.connect([7; 32]).await;
        for n in 0..6 {
            db.challenge("w".into(), "m".into(), n.to_string(), 20, 10)
                .await
                .unwrap();
        }
        assert_eq!(db.nonce("w".into(), "5".into(), 10).await.unwrap(), "m");
        db.challenge("w".into(), "fresh".into(), "6".into(), 30, 20)
            .await
            .unwrap();
        assert!(db.nonce("w".into(), "5".into(), 20).await.is_err());
        let count: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM challenges")
            .fetch_one(&db.pool)
            .await
            .unwrap();
        assert_eq!(count, 1);
        test.cleanup(&[&db]).await;
    }
    #[tokio::test]
    async fn challenge_capacity_is_atomic_across_pools_and_cleans_expiry() {
        let test = TestDatabase::create().await;
        let first = test.connect([7; 32]).await;
        let second = test.connect([7; 32]).await;
        sqlx::query(
            "INSERT INTO challenges(nonce,wallet,message,expires)
            SELECT n::text,'w','m',20 FROM generate_series(1,9999) AS n",
        )
        .execute(&first.pool)
        .await
        .unwrap();
        let (a, b) = tokio::join!(
            first.challenge("other".into(), "m".into(), "a".into(), 20, 10),
            second.challenge("other".into(), "m".into(), "b".into(), 20, 10),
        );
        assert_ne!(a.is_ok(), b.is_ok());
        let error = match a {
            Err(error) => error,
            Ok(()) => b.unwrap_err(),
        };
        assert_eq!(error.0, axum::http::StatusCode::TOO_MANY_REQUESTS);
        let count: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM challenges")
            .fetch_one(&first.pool)
            .await
            .unwrap();
        assert_eq!(count, 10_000);
        first
            .challenge("other".into(), "m".into(), "fresh".into(), 30, 20)
            .await
            .unwrap();
        let count: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM challenges")
            .fetch_one(&first.pool)
            .await
            .unwrap();
        assert_eq!(count, 1);
        test.cleanup(&[&first, &second]).await;
    }
    #[tokio::test]
    async fn reservations_serialize_conflicts_across_pools() {
        let test = TestDatabase::create().await;
        let first = test.connect([7; 32]).await;
        let second = test.connect([7; 32]).await;
        let (a, b) = tokio::join!(
            first.reserve(post("a", "w"), [9; 32], 1),
            second.reserve(post("b", "w"), [9; 32], 1),
        );
        assert_ne!(a.is_ok(), b.is_ok());
        let winner = if a.is_ok() { "a" } else { "b" };
        let (a, b) = tokio::join!(
            first.reserve(post(winner, "w"), [9; 32], 2),
            second.reserve(post(winner, "w"), [9; 32], 2),
        );
        assert!(a.is_ok() && b.is_ok());
        assert!(first.reserve(post(winner, "w"), [10; 32], 2).await.is_err());
        test.cleanup(&[&first, &second]).await;
    }
    #[tokio::test]
    async fn keys_are_wrapped_bound_and_survive_reconnect() {
        let test = TestDatabase::create().await;
        let db = test.connect([8; 32]).await;
        let p = post("c", "w");
        db.reserve(p.clone(), [9; 32], 1).await.unwrap();
        assert_eq!(db.post_key(p.clone()).await.unwrap().as_slice(), &[9; 32]);
        let mut forged = p.clone();
        forged.wallet = "attacker".into();
        assert!(db.post_key(forged).await.is_err());
        let wrapped: Vec<u8> = sqlx::query_scalar("SELECT wrapped_key FROM posts")
            .fetch_one(&db.pool)
            .await
            .unwrap();
        assert_ne!(wrapped, &[9; 32]);
        assert_eq!(wrapped.len(), 60);
        db.pool.close().await;
        assert!(Db::connect(test.options.clone(), 2, [7; 32]).await.is_err());
        let reopened = test.connect([8; 32]).await;
        assert_eq!(reopened.post_key(p).await.unwrap().as_slice(), &[9; 32]);
        test.cleanup(&[&reopened]).await;
    }
    #[tokio::test]
    async fn feed_hides_pending_and_cursors_are_stable() {
        let test = TestDatabase::create().await;
        let db = test.connect([7; 32]).await;
        for id in ["a", "b", "c"] {
            db.reserve(post(id, id), [9; 32], 1).await.unwrap();
        }
        db.mark_published("a".into()).await.unwrap();
        db.mark_published("c".into()).await.unwrap();
        let page = db.feed(10, "".into(), 1).await.unwrap();
        assert_eq!(page[0].commitment, "a");
        let page = db.feed(10, "a".into(), 10).await.unwrap();
        assert_eq!(page.len(), 1);
        assert_eq!(page[0].commitment, "c");
        assert!(db.feed(11, "".into(), 10).await.unwrap().is_empty());
        test.cleanup(&[&db]).await;
    }
    #[tokio::test]
    async fn blobs_are_content_addressed_immutable_and_checked() {
        let dir = tempfile::tempdir().unwrap();
        let store = ObjectBlobs::local(dir.path().to_str().unwrap()).unwrap();
        let bytes = b"encrypted data".to_vec();
        let h = protocol::hash(&bytes);
        store.put(h, bytes.clone()).await.unwrap();
        store.put(h, bytes.clone()).await.unwrap();
        assert_eq!(store.get(h).await.unwrap(), bytes);
        assert!(store.put(h, b"different".to_vec()).await.is_err());
        std::fs::write(
            dir.path().join(format!("moments/{}", hex::encode(h))),
            b"corrupt",
        )
        .unwrap();
        assert!(store.get(h).await.is_err());
        assert!(store.put(h, bytes).await.is_err());
        assert!(store.get([0; 32]).await.is_err());
        let huge = vec![0; MAX_BLOB + 1];
        let huge_hash = protocol::hash(&huge);
        assert!(store.put(huge_hash, huge.clone()).await.is_err());
        std::fs::write(
            dir.path()
                .join(format!("moments/{}", hex::encode(huge_hash))),
            huge,
        )
        .unwrap();
        assert!(store.get(huge_hash).await.is_err());
        assert!(ObjectBlobs::r2("http://r2.example", "b", "a", "s").is_err());
    }
    #[tokio::test]
    async fn r2_requests_are_signed_conditional_and_content_checked() {
        use axum::{
            body::{to_bytes, Body},
            extract::State,
            http::{Request, StatusCode},
            response::Response,
            routing::any,
            Router,
        };
        #[derive(Default)]
        struct Mock {
            body: Option<Vec<u8>>,
            puts: usize,
            gets: usize,
        }
        type Shared = Arc<Mutex<Mock>>;
        async fn handle(State(state): State<Shared>, request: Request<Body>) -> Response {
            let (parts, body) = request.into_parts();
            let auth = parts.headers["authorization"].to_str().unwrap();
            assert!(auth.contains("Credential=test-access/"));
            assert!(auth.contains("/auto/s3/aws4_request"));
            let expected = hex::encode(protocol::hash(b"encrypted R2 packet"));
            assert_eq!(
                parts.uri.path(),
                format!("/private-bucket/moments/{expected}")
            );
            match parts.method {
                axum::http::Method::PUT => {
                    assert_eq!(parts.headers["if-none-match"], "*");
                    let bytes = to_bytes(body, MAX_BLOB).await.unwrap().to_vec();
                    let mut mock = state.lock().unwrap();
                    mock.puts += 1;
                    if mock.body.is_some() {
                        return Response::builder().status(StatusCode::PRECONDITION_FAILED)
                            .header("content-type", "application/xml")
                            .body(Body::from("<Error><Code>PreconditionFailed</Code><Message>Already exists</Message></Error>"))
                            .unwrap();
                    }
                    mock.body = Some(bytes);
                    Response::builder()
                        .status(StatusCode::OK)
                        .header("etag", "\"test-etag\"")
                        .body(Body::empty())
                        .unwrap()
                }
                axum::http::Method::GET => {
                    assert_eq!(parts.headers["range"], format!("bytes=0-{MAX_BLOB}"));
                    let mut mock = state.lock().unwrap();
                    mock.gets += 1;
                    let bytes = mock.body.clone().unwrap();
                    Response::builder()
                        .status(StatusCode::PARTIAL_CONTENT)
                        .header("content-length", bytes.len())
                        .header(
                            "content-range",
                            format!("bytes 0-{}/{}", bytes.len() - 1, bytes.len()),
                        )
                        .header("last-modified", "Mon, 21 Sep 2026 00:00:00 GMT")
                        .header("etag", "\"test-etag\"")
                        .body(Body::from(bytes))
                        .unwrap()
                }
                method => panic!("unexpected method {method}"),
            }
        }
        let shared = Arc::new(Mutex::new(Mock::default()));
        let app = Router::new()
            .fallback(any(handle))
            .with_state(shared.clone());
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let endpoint = format!("http://{}", listener.local_addr().unwrap());
        let server = tokio::spawn(async move {
            axum::serve(listener, app).await.unwrap();
        });
        // Only this test bypasses HTTPS. All other production builder options remain identical.
        let store = ObjectBlobs {
            store: Arc::new(
                ObjectBlobs::r2_builder(&endpoint, "private-bucket", "test-access", "test-secret")
                    .with_allow_http(true)
                    .build()
                    .unwrap(),
            ),
        };
        let bytes = b"encrypted R2 packet".to_vec();
        let reference = protocol::hash(&bytes);
        store.put(reference, bytes.clone()).await.unwrap();
        store.put(reference, bytes.clone()).await.unwrap();
        assert_eq!(store.get(reference).await.unwrap(), bytes);
        {
            let mut mock = shared.lock().unwrap();
            assert_eq!((mock.puts, mock.gets), (2, 2));
            mock.body = Some(b"corrupted R2 packet".to_vec());
        }
        assert!(store.get(reference).await.is_err());
        assert!(store.put(reference, bytes).await.is_err());
        server.abort();
    }
}
