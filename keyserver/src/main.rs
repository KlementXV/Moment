use anyhow::{bail, Context};
use moment_keyserver::{
    api::{self, App, SystemClock},
    chain::{Chain, RpcChain},
    config::{Settings, Storage},
    moderation::OnnxReviewer,
    store::{Db, ObjectBlobs},
};
use std::sync::Arc;
use tokio::sync::Semaphore;

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    // No request-body/header/SDK debug logging, even if RUST_LOG is set externally.
    tracing_subscriber::fmt()
        .with_max_level(tracing::Level::INFO)
        .with_target(false)
        .init();
    let settings = Settings::from_env()?;
    let blobs = match &settings.storage {
        Storage::Local(path) => ObjectBlobs::local(path)?,
        Storage::R2 {
            endpoint,
            bucket,
            access,
            secret,
        } => ObjectBlobs::r2(endpoint, bucket, access, secret)?,
    };
    let db = Db::connect(
        settings.database,
        settings.database_max_connections,
        *settings.master_key,
    )
    .await
    .context("Ouverture de la base impossible")?;
    let chain = Arc::new(RpcChain::new(settings.rpc_url, settings.program)?);
    chain
        .verify_network(&settings.network)
        .await
        .context("Le RPC ne correspond pas au réseau configuré")?;
    let config = chain
        .config()
        .await
        .context("Configuration Solana inaccessible")?;
    if config.authority != settings.authority.verifying_key().to_bytes() {
        bail!("La clé serveur ne correspond pas à Config.publication_authority. Effectuez la rotation on-chain avant de démarrer.");
    }
    // Dynamic runtime is provided by the operator; never download native code at startup.
    if !settings.ort_library.is_file() {
        bail!("ORT_DYLIB_PATH doit pointer vers la bibliothèque ONNX Runtime installée");
    }
    ort::init_from(settings.ort_library.to_string_lossy())
        .with_name("moment-keyserver")
        .commit()
        .context("Initialisation ONNX Runtime impossible")?;
    let reviewer = OnnxReviewer::new(
        &settings.model,
        &settings.policy,
        settings.allow_uncalibrated,
    )?;
    if settings.allow_uncalibrated {
        tracing::warn!("Modération expérimentale activée sur devnet : seuils non calibrés");
    }
    let crank_chain = chain.clone();
    let app = Arc::new(App {
        db,
        blobs: Arc::new(blobs),
        chain,
        reviewer: Arc::new(reviewer),
        clock: Arc::new(SystemClock),
        network: settings.network,
        program: settings.program,
        origin: settings.auth_origin,
        authority: settings.authority,
        inference_slots: Arc::new(Semaphore::new(1)),
    });
    match settings.crank {
        Some(signer) => {
            tokio::spawn(moment_keyserver::crank::run(crank_chain, settings.program, signer));
            tracing::info!("Crank reap actif (00:05 et clôture + 5 min UTC)");
        }
        None => tracing::info!("Crank reap désactivé : CRANK_KEYPAIR absent"),
    }
    let listener = tokio::net::TcpListener::bind(settings.bind)
        .await
        .context("Écoute HTTP impossible")?;
    tracing::info!(address = %settings.bind, "Moment keyserver prêt");
    axum::serve(
        listener,
        api::router_with_trusted_proxies(app, settings.trusted_proxies)
            .into_make_service_with_connect_info::<std::net::SocketAddr>(),
    )
    .with_graceful_shutdown(shutdown())
    .await
    .context("Arrêt du serveur HTTP")?;
    Ok(())
}

async fn shutdown() {
    #[cfg(unix)]
    {
        if let Ok(mut signal) =
            tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate())
        {
            tokio::select! { _ = tokio::signal::ctrl_c() => {}, _ = signal.recv() => {} }
        } else {
            let _ = tokio::signal::ctrl_c().await;
        }
    }
    #[cfg(not(unix))]
    {
        let _ = tokio::signal::ctrl_c().await;
    }
}
