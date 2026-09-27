use crate::protocol::{address, key64, Key};
use anyhow::{bail, Context};
use ed25519_dalek::SigningKey;
use sqlx::postgres::{PgConnectOptions, PgSslMode};
use std::{env, net::SocketAddr, path::PathBuf};
use zeroize::Zeroizing;

pub struct Settings {
    pub bind: SocketAddr,
    pub trusted_proxies: Vec<ipnet::IpNet>,
    pub rpc_url: String,
    pub program: Key,
    pub network: String,
    pub auth_origin: String,
    pub authority: SigningKey,
    pub master_key: Zeroizing<Key>,
    pub database: PgConnectOptions,
    pub database_max_connections: u32,
    pub storage: Storage,
    pub model: PathBuf,
    pub policy: PathBuf,
    pub ort_library: PathBuf,
    pub allow_uncalibrated: bool,
    pub crank: Option<SigningKey>,
}
pub enum Storage {
    Local(String),
    R2 {
        endpoint: String,
        bucket: String,
        access: String,
        secret: Zeroizing<String>,
    },
}

fn required(name: &str) -> anyhow::Result<String> {
    env::var(name)
        .ok()
        .filter(|v| !v.trim().is_empty())
        .with_context(|| format!("Variable {name} manquante"))
}

fn check_url(value: &str, allow_local: bool) -> anyhow::Result<()> {
    let url =
        reqwest::Url::parse(value).map_err(|_| anyhow::anyhow!("URL de configuration invalide"))?;
    let local = matches!(url.host_str(), Some("localhost" | "127.0.0.1" | "[::1]"));
    if url.host_str().is_none()
        || !url.username().is_empty()
        || url.password().is_some()
        || url.fragment().is_some()
        || (url.scheme() != "https" && !(allow_local && local && url.scheme() == "http"))
    {
        bail!("Utilisez une URL HTTPS (HTTP accepté uniquement sur loopback en devnet)");
    }
    Ok(())
}

impl Settings {
    pub fn from_env() -> anyhow::Result<Self> {
        let network = required("NETWORK")?;
        if !matches!(network.as_str(), "devnet" | "mainnet") {
            bail!("NETWORK doit valoir devnet ou mainnet");
        }
        let dev = network == "devnet";
        let rpc_url = required("RPC_URL")?;
        check_url(&rpc_url, dev)?;
        let auth_origin = required("AUTH_ORIGIN")?;
        check_url(&auth_origin, dev)?;
        let origin = reqwest::Url::parse(&auth_origin)?;
        if origin.query().is_some() || origin.path() != "/" {
            bail!("AUTH_ORIGIN doit être une origine sans chemin ni query");
        }
        let authority = load_keypair(&required("PUBLICATION_AUTHORITY_KEYPAIR")?)
            .context("Clé de publication illisible")?;
        let crank = crank_key(env::var("CRANK_KEYPAIR").ok(), &authority)?;
        let encoded = Zeroizing::new(required("KEY_ENCRYPTION_KEY")?);
        let master_key = Zeroizing::new(
            key64(&encoded)
                .context("KEY_ENCRYPTION_KEY doit être une clé aléatoire de 32 octets en base64")?,
        );
        let storage = match required("BLOB_STORE")?.as_str() {
            "local" if dev => Storage::Local(required("BLOB_DIR")?),
            "r2" => {
                let endpoint = required("R2_ENDPOINT")?;
                check_url(&endpoint, false)?;
                Storage::R2 {
                    endpoint,
                    bucket: required("R2_BUCKET")?,
                    access: required("R2_ACCESS_KEY_ID")?,
                    secret: Zeroizing::new(required("R2_SECRET_ACCESS_KEY")?),
                }
            }
            _ => bail!("BLOB_STORE doit valoir r2 (ou local en devnet)"),
        };
        let allow_uncalibrated = match env::var("ALLOW_UNCALIBRATED_MODERATION").as_deref() {
            Ok("true") if dev => true,
            Ok("false") | Err(_) => false,
            _ => bail!("ALLOW_UNCALIBRATED_MODERATION=true est réservé à devnet"),
        };
        let pg_host = required("PGHOST")?;
        let ssl_mode = match env::var("PGSSLMODE").as_deref() {
            Ok("verify-full") | Err(_) => PgSslMode::VerifyFull,
            Ok("disable") if dev && matches!(pg_host.as_str(), "127.0.0.1" | "localhost" | "::1") => PgSslMode::Disable,
            _ => bail!("PGSSLMODE doit valoir verify-full ; disable est réservé au PostgreSQL local sur devnet"),
        };
        let pg_password = Zeroizing::new(required("PGPASSWORD")?);
        let mut database = PgConnectOptions::new()
            .host(&pg_host)
            .port(
                env::var("PGPORT")
                    .unwrap_or_else(|_| "5432".into())
                    .parse()
                    .context("PGPORT invalide")?,
            )
            .username(&required("PGUSER")?)
            .password(&pg_password)
            .database(&required("PGDATABASE")?)
            .ssl_mode(ssl_mode)
            .application_name("moment-keyserver");
        if let Ok(root_cert) = env::var("PGSSLROOTCERT") {
            if !std::path::Path::new(&root_cert).is_file() {
                bail!("PGSSLROOTCERT doit être un certificat CA lisible");
            }
            database = database.ssl_root_cert(root_cert);
        }
        let database_max_connections: u32 = env::var("DATABASE_MAX_CONNECTIONS")
            .unwrap_or_else(|_| "10".into())
            .parse()
            .context("DATABASE_MAX_CONNECTIONS invalide")?;
        if !(1..=100).contains(&database_max_connections) {
            bail!("DATABASE_MAX_CONNECTIONS doit être entre 1 et 100");
        }
        let trusted_proxies = env::var("TRUSTED_PROXY_CIDRS")
            .unwrap_or_default()
            .split(',')
            .filter(|value| !value.trim().is_empty())
            .map(|value| {
                value
                    .trim()
                    .parse::<ipnet::IpNet>()
                    .context("TRUSTED_PROXY_CIDRS doit contenir des CIDR IPv4/IPv6")
            })
            .collect::<anyhow::Result<Vec<_>>>()?;
        if trusted_proxies.iter().any(|net| net.prefix_len() == 0) {
            bail!("TRUSTED_PROXY_CIDRS ne doit pas faire confiance à tout Internet");
        }
        Ok(Self {
            trusted_proxies,
            bind: env::var("BIND_ADDR")
                .unwrap_or_else(|_| "127.0.0.1:8080".into())
                .parse()
                .context("BIND_ADDR invalide")?,
            rpc_url,
            program: address(&required("PROGRAM_ID")?).context("PROGRAM_ID invalide")?,
            network,
            auth_origin: origin.origin().ascii_serialization(),
            authority,
            master_key,
            database,
            database_max_connections,
            storage,
            model: required("MODERATION_MODEL_PATH")?.into(),
            policy: required("MODERATION_POLICY_PATH")?.into(),
            ort_library: required("ORT_DYLIB_PATH")?.into(),
            allow_uncalibrated,
            crank,
        })
    }
}

pub struct CrankSettings {
    pub rpc_url: String,
    pub program: Key,
    pub network: String,
    pub signer: SigningKey,
}

impl CrankSettings {
    pub fn from_env() -> anyhow::Result<Self> {
        Self::from_lookup(|name| env::var(name).ok())
    }

    pub fn from_lookup(get: impl Fn(&str) -> Option<String>) -> anyhow::Result<Self> {
        let need = |name: &str| {
            get(name)
                .filter(|v| !v.trim().is_empty())
                .with_context(|| format!("Variable {name} manquante"))
        };
        let network = need("NETWORK")?;
        if !matches!(network.as_str(), "devnet" | "mainnet") {
            bail!("NETWORK doit valoir devnet ou mainnet");
        }
        let rpc_url = need("RPC_URL")?;
        check_url(&rpc_url, network == "devnet")?;
        let program = address(&need("PROGRAM_ID")?).context("PROGRAM_ID invalide")?;
        let signer = load_keypair(&need("CRANK_KEYPAIR")?).context("CRANK_KEYPAIR illisible")?;
        Ok(Self {
            rpc_url,
            program,
            network,
            signer,
        })
    }
}

fn load_keypair(path: &str) -> anyhow::Result<SigningKey> {
    let bytes = Zeroizing::new(std::fs::read(path).context("Impossible de lire la clé")?);
    let decoded: Zeroizing<Vec<u8>> =
        Zeroizing::new(serde_json::from_slice(&bytes).context("Format de clé Solana invalide")?);
    let pair: Zeroizing<[u8; 64]> = Zeroizing::new(
        decoded
            .as_slice()
            .try_into()
            .context("La clé Solana doit contenir 64 octets")?,
    );
    SigningKey::from_keypair_bytes(&pair).context("Clé Solana incohérente")
}

fn crank_key(path: Option<String>, authority: &SigningKey) -> anyhow::Result<Option<SigningKey>> {
    let Some(path) = path.filter(|p| !p.trim().is_empty()) else {
        return Ok(None);
    };
    let key = load_keypair(&path).context("CRANK_KEYPAIR illisible")?;
    if key.verifying_key() == authority.verifying_key() {
        bail!("CRANK_KEYPAIR doit être distincte de la clé de publication");
    }
    Ok(Some(key))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    fn keyfile(seed: u8) -> tempfile::NamedTempFile {
        let key = SigningKey::from_bytes(&[seed; 32]);
        let mut file = tempfile::NamedTempFile::new().unwrap();
        let bytes: Vec<u8> = key.to_keypair_bytes().to_vec();
        write!(file, "{}", serde_json::to_string(&bytes).unwrap()).unwrap();
        file
    }

    fn crank_vars(key: &tempfile::NamedTempFile, rpc: &str) -> impl Fn(&str) -> Option<String> {
        let path = key.path().to_string_lossy().into_owned();
        let rpc = rpc.to_owned();
        move |name: &str| match name {
            "NETWORK" => Some("devnet".into()),
            "RPC_URL" => Some(rpc.clone()),
            "PROGRAM_ID" => Some("ANT4AF24p1io1pmdNFKd9RKMStLCFi6WGbu81QoGzqN6".into()),
            "CRANK_KEYPAIR" => Some(path.clone()),
            _ => None,
        }
    }

    #[test]
    fn crank_mode_needs_only_the_rpc_the_program_and_its_own_key() {
        let file = keyfile(2);
        let settings = CrankSettings::from_lookup(crank_vars(&file, "https://api.devnet.solana.com")).unwrap();
        assert_eq!(settings.signer.to_bytes(), [2; 32]);
        assert_eq!(settings.network, "devnet");
        assert_eq!(protocol_address(&settings.program), "ANT4AF24p1io1pmdNFKd9RKMStLCFi6WGbu81QoGzqN6");
    }

    #[test]
    fn crank_mode_refuses_a_missing_key_or_an_insecure_rpc() {
        let file = keyfile(2);
        let no_key = |name: &str| if name == "CRANK_KEYPAIR" { None } else { crank_vars(&file, "https://api.devnet.solana.com")(name) };
        assert!(CrankSettings::from_lookup(no_key).is_err());
        assert!(CrankSettings::from_lookup(crank_vars(&file, "http://rpc.example.com")).is_err());
    }

    fn protocol_address(key: &Key) -> String {
        crate::protocol::address_string(key)
    }

    #[test]
    fn the_crank_is_optional() {
        let authority = SigningKey::from_bytes(&[1; 32]);
        assert!(crank_key(None, &authority).unwrap().is_none());
        assert!(crank_key(Some("  ".into()), &authority).unwrap().is_none());
    }

    #[test]
    fn the_crank_key_is_loaded_from_a_solana_keypair_file() {
        let authority = SigningKey::from_bytes(&[1; 32]);
        let file = keyfile(2);
        let key = crank_key(Some(file.path().to_string_lossy().into()), &authority)
            .unwrap()
            .unwrap();
        assert_eq!(key.to_bytes(), [2; 32]);
    }

    #[test]
    fn the_crank_never_reuses_the_publication_authority() {
        let authority = SigningKey::from_bytes(&[1; 32]);
        let file = keyfile(1);
        assert!(crank_key(Some(file.path().to_string_lossy().into()), &authority).is_err());
    }
}
