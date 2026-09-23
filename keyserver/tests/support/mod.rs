use moment_keyserver::{protocol::Key, store::Db};
use sqlx::{
    postgres::{PgConnectOptions, PgPoolOptions},
    PgPool,
};

/// Each API test owns a PostgreSQL schema. TEST_DATABASE_URL must target a disposable DB.
pub struct Database {
    pub db: Db,
    admin: PgPool,
    schema: String,
}
impl Database {
    pub async fn create(key: Key) -> Self {
        let url = std::env::var("TEST_DATABASE_URL")
            .expect("TEST_DATABASE_URL is required (use the disposable PostgreSQL test service)");
        let options: PgConnectOptions = url.parse().expect("valid TEST_DATABASE_URL");
        let admin = PgPoolOptions::new()
            .max_connections(1)
            .connect_with(options.clone())
            .await
            .expect("test PostgreSQL reachable");
        let schema = format!("api_test_{}", hex::encode(rand::random::<[u8; 16]>()));
        sqlx::query(&format!("CREATE SCHEMA {schema}"))
            .execute(&admin)
            .await
            .unwrap();
        let db = Db::connect(options.options([("search_path", schema.as_str())]), 3, key)
            .await
            .unwrap();
        Self { db, admin, schema }
    }
}
impl Drop for Database {
    fn drop(&mut self) {
        // Best-effort cleanup when a test panics too. The disposable service is the final boundary.
        let pool = self.admin.clone();
        let schema = self.schema.clone();
        if let Ok(runtime) = tokio::runtime::Handle::try_current() {
            runtime.spawn(async move {
                let _ = sqlx::query(&format!("DROP SCHEMA {schema} CASCADE"))
                    .execute(&pool)
                    .await;
                pool.close().await;
            });
        }
    }
}
