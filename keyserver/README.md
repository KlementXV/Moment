# Moment keyserver

The Rust backend for Moment handles wallet authentication, photo moderation, encrypted publication, transaction cosigning, and access to the daily community feed.

It uses Axum, PostgreSQL, ONNX Runtime, and either local encrypted storage or a private Cloudflare R2 bucket. The Android app and backend share the `moment-manifest-v1` signed manifest and `moment-post-v1` encrypted packet format.

## Trust model

The backend can decrypt photos to moderate them and controls access to their keys. R2 receives encrypted packets without decryption keys. The publication authority can authorize `check_in`; it cannot withdraw users' stakes. User wallet private keys are never sent to the server.

PostgreSQL holds session hashes, publication reservations, post metadata, and photo keys encrypted with a master key. All backend replicas must use the same master key and database. Keep the master key backed up separately: neither PostgreSQL backups nor R2 blobs alone can recover the complete publication data.

## Local setup

Requirements:

- Rust compatible with the repository's dependencies.
- PostgreSQL 17.
- ONNX Runtime CPU **1.23.2**, including its native dependencies.
- The bundled model and policy in `app/app/src/main/assets/moderation/`.
- A Solana RPC endpoint and a program configuration with the matching publication authority.

```sh
cd keyserver
cargo build --locked --release
mkdir -p data secrets
chmod 700 data secrets
cp .env.example .env
chmod 600 .env
```

Fill in `.env`. The executable does not load it automatically; source it from the `keyserver/` directory:

```sh
set -a
. ./.env
set +a
./target/release/moment-keyserver
```

Relative paths resolve from the working directory. Install the native library from the [official ONNX Runtime releases](https://github.com/microsoft/onnxruntime/releases) and set `ORT_DYLIB_PATH` to its absolute path. No model or native library is downloaded at startup.

### Configuration

| Setting | Purpose |
| --- | --- |
| `BIND_ADDR` | HTTP listener, normally behind an HTTPS proxy |
| `NETWORK`, `RPC_URL`, `PROGRAM_ID` | Solana deployment |
| `AUTH_ORIGIN` | Exact public origin included in wallet authentication challenges |
| `PUBLICATION_AUTHORITY_KEYPAIR` | Dedicated Solana keypair matching `Config.publication_authority` |
| `KEY_ENCRYPTION_KEY` | Base64-encoded 32-byte master key for persisted photo keys |
| `PGHOST`, `PGPORT`, `PGDATABASE`, `PGUSER`, `PGPASSWORD` | PostgreSQL connection |
| `PGSSLMODE`, `PGSSLROOTCERT` | TLS verification and server CA |
| `DATABASE_MAX_CONNECTIONS` | Connection limit per backend instance; default 10 |
| `BLOB_STORE` | `r2` or `local` |
| `BLOB_DIR` | Directory used by local blob storage |
| `R2_ENDPOINT`, `R2_BUCKET`, `R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY` | Private R2 storage |
| `MODERATION_MODEL_PATH`, `MODERATION_POLICY_PATH`, `ORT_DYLIB_PATH` | Moderation assets and native inference library |
| `ALLOW_UNCALIBRATED_MODERATION` | Explicit opt-in for experimental moderation on devnet only |
| `TRUSTED_PROXY_CIDRS` | Comma-separated networks allowed to supply forwarded client addresses |
| `CRANK_KEYPAIR` | Optional, separate SOL-funded keypair for scheduled pool settlement |

Generate the master key once with `openssl rand -base64 32` and preserve it. Changing the value does not migrate encrypted data. PostgreSQL migrations run at startup under a database lock; the application role must be able to create tables and indexes.

`PGSSLMODE=verify-full` is required outside local devnet development. For a disposable PostgreSQL service on loopback, devnet may use `PGSSLMODE=disable` with `PGSSLROOTCERT` unset.

Startup checks the RPC network, publication authority, encryption configuration, moderation policy, and a real warm-up inference before opening HTTP. The bundled moderation thresholds are experimental. Local devnet testing requires `ALLOW_UNCALIBRATED_MODERATION=true`; this bypass is rejected on mainnet.

### Private R2 storage

Create a dedicated bucket with public access and `r2.dev` disabled. Use S3 credentials scoped to that bucket. The backend neither creates the bucket nor changes its access policy. Configure the endpoint supplied by Cloudflare, normally `https://<ACCOUNT_ID>.r2.cloudflarestorage.com`.

The app accesses blobs through the authenticated backend and receives no R2 credentials or public object URLs. For local development, set `BLOB_STORE=local` and configure `BLOB_DIR`; encryption and access checks remain enabled.

## HTTP API

Protected routes require `Authorization: Bearer <token>`. Responses use `Cache-Control: no-store, private`. Errors return `{"error":"..."}`. Addresses use base58; hashes use 64 lowercase hexadecimal characters; binary payloads use padded base64. Timestamps are Unix seconds, and `day` is `floor(timestamp / 86400)`.

| Method | Route | Purpose |
| --- | --- | --- |
| `POST` | `/v1/session/challenge` | Request a wallet authentication challenge |
| `POST` | `/v1/session/verify` | Verify the signature and create a session |
| `POST` | `/v1/posts` | Reserve a publication and cosign its transaction |
| `POST` | `/v1/posts/{commitment}/confirm` | Confirm the on-chain check-in |
| `GET` | `/v1/feed` | Retrieve eligible, confirmed posts for today |
| `GET` | `/v1/blobs/{blobRef}` | Retrieve an authorized encrypted packet |
| `PUT` / `DELETE` | `/v1/posts/{commitment}/like` | Like or unlike an eligible post |
| `GET` | `/healthz` | Check the HTTP process |
| `GET` | `/readyz` | Check PostgreSQL connectivity |

### Wallet authentication

Send `{"wallet":"<base58>"}` to `/v1/session/challenge`. The response contains `nonce`, `message`, and `expiresAt`. Sign the exact UTF-8 bytes of `message`: it binds the public origin, network, program, wallet, nonce, and expiry.

Send `wallet`, `nonce`, and the base64 signature to `/v1/session/verify`. The response contains `token` and `expiresAt`. Challenges last five minutes; sessions last fifteen minutes. Challenge consumption and session creation are atomic in PostgreSQL.

### Signed manifest and encrypted packet

The canonical manifest is defined by [PostManifest.kt](../app/app/src/main/java/com/klementxv/moment/provenance/PostManifest.kt). It binds the network, program, wallet, day, nonce, and ordered hashes of the sanitized rear photo and selfie. The wallet signs it with Ed25519.

The plaintext packet layout is:

| Field | Encoding |
| --- | --- |
| Domain | ASCII `moment-post-v1` |
| Version | `u8`, version 1 or 2 |
| Manifest | Little-endian `u32` length, then manifest bytes |
| Wallet signature | Little-endian `u32` length of 64, then signature |
| Rear photo | Little-endian `u32` length, then JPEG bytes |
| Selfie | Little-endian `u32` length, then JPEG bytes |
| Caption, version 2 only | Little-endian `u32` length, then UTF-8 bytes; may be empty |

The app currently writes version 2. Captions are encrypted with the packet but are outside the signed photo manifest. Each JPEG is limited to 4 MiB and a maximum edge of 1,280 pixels. Metadata is rejected; only a bare JFIF APP0 segment is permitted.

Use a fresh 32-byte key and 12-byte nonce per publication. Encrypt with AES-256-GCM and ASCII `moment-post-v1` as additional authenticated data:

```text
blob       = nonce[12] || ciphertext || authentication_tag[16]
blobRef    = SHA256(blob)
commitment = SHA256(manifest)
```

### Publication and retries

`POST /v1/posts` accepts `day`, `commitment`, `blobRef`, `postKey`, `blob`, and `transaction`. Binary values are base64-encoded. The backend verifies the packet, manifest signature, image hashes, moderation results, profile eligibility, blockhash, and exact transaction instructions and accounts.

Only the permitted `check_in` transaction and supported compute-budget instructions are accepted. The wallet is the fee payer, and the publication authority is the second signer. No token transfer is authorized by this endpoint. Both image scores must be below the server review threshold; local acknowledgement cannot override server moderation.

The response contains `state: "authorized"`, the references, and the cosigned transaction. The blob and wrapped key are persisted before returning the signature. The client preserves both signatures, submits the transaction to Solana, and waits for confirmation.

Retry with the same encrypted packet, key, and references. A fresh blockhash is allowed after validation. A different publication for the same wallet and day is rejected with HTTP 409. Do not re-encrypt the photos during a retry.

After chain confirmation, call `/v1/posts/{commitment}/confirm`. The server checks the `CheckIn` account and stored blob before marking the publication visible. Confirmation is idempotent and can be retried without a new transaction.

### Feed access

Use `/v1/feed?day=<UTC-day>&limit=20&after=<optional-cursor>`. Pages contain `items` and `nextCursor`; the maximum page size is 50. Pass the returned cursor as `after` to retrieve the next page.

The feed, blob, and like routes check the current day, session, reader check-in, effective stake, and publication eligibility. A database `published` flag alone does not grant access. RPC failure closes access. The client verifies hashes, encryption integrity, the manifest, and wallet signature before displaying photos.

Earlier days are not available through these routes. Access expiry cannot revoke photos or keys already downloaded by a recipient.

## Operations

HTTP requests are bounded to 12 MiB, sixteen concurrent requests, one concurrent inference, and a 45-second request timeout. Feed checks run with up to eight concurrent publication checks per request.

The IP quota is 120 requests per minute per pod. Behind a proxy, configure only its trusted networks in `TRUSTED_PROXY_CIDRS`. Forwarded addresses are resolved from right to left to the first untrusted peer. Add proxy-level limits if a quota must be shared across replicas. Authentication challenge capacity is shared through PostgreSQL.

`/readyz` checks PostgreSQL, not the complete RPC/R2 dependency chain. Health probes bypass the HTTP quota. Do not log sensitive request bodies or authorization headers at the proxy.

There is no automatic post/blob retention cleanup. Define coordinated database/object retention before long-running operation. Back up PostgreSQL, private R2 objects, and the encryption master key independently and validate recovery together.

## Docker image

CI does not publish the keyserver image; build it yourself from [`Dockerfile`](Dockerfile). The build context must be the **repository root**, because the image also bundles the moderation model and policy from `app/app/src/main/assets/moderation/`. The Dockerfile uses BuildKit cache mounts, which current Docker Desktop and Docker Engine enable by default.

From the repository root:

```sh
docker build -f keyserver/Dockerfile -t moment-keyserver:local .
```

The image contains the release binary, ONNX Runtime CPU 1.23.2, and the moderation assets. It runs as an unprivileged user and listens on port `8080`. Supported platforms are `linux/amd64` and `linux/arm64`. The image targets the build machine's architecture by default. To build an image for an x86_64 server from an Apple Silicon Mac, set the platform explicitly; the Rust build then runs under emulation and is much slower:

```sh
docker build --platform linux/amd64 -f keyserver/Dockerfile -t moment-keyserver:local .
```

The container needs the same configuration as a local run, including PostgreSQL, the publication keypair, and the encryption master key. Use the Compose deployment below rather than a bare `docker run`; it builds this image itself.

For the [Helm chart](helm/moment-keyserver/README.md), push the image to a registry that your cluster can pull from, and reference it in `image.repository` and `image.tag`:

```sh
docker tag moment-keyserver:local ghcr.io/YOUR_ACCOUNT/moment-keyserver:0.3.0
docker push ghcr.io/YOUR_ACCOUNT/moment-keyserver:0.3.0
```

## Docker Compose on a VPS (devnet)

[`compose.prod.yaml`](compose.prod.yaml) runs the keyserver and a persistent PostgreSQL 17 instance. It does not start Traefik or expose PostgreSQL. The API is published only on the VPS loopback address, `127.0.0.1:8080` by default. PostgreSQL is on a private Docker network; an initialization job creates a private CA and server certificate so the keyserver can use `PGSSLMODE=verify-full`. The backend image is built locally from the repository, so GHCR access is not required.

From `keyserver/`:

```sh
cp compose.prod.env.example .env.prod
chmod 600 .env.prod
mkdir -p secrets
chmod 750 secrets
```

Edit `.env.prod`: set `AUTH_ORIGIN` to the exact public HTTPS origin used by the Android app, confirm `PROGRAM_ID` and `RPC_URL`, set a strong `PGPASSWORD` (for example, from `openssl rand -hex 32`) and a stable `KEY_ENCRYPTION_KEY` (`openssl rand -base64 32`), and fill in private R2 bucket credentials. The R2 bucket must have public access disabled. Keep `NETWORK=devnet` and `ALLOW_UNCALIBRATED_MODERATION=true` only for the current experimental devnet policy. The keyserver will refuse to start if its authority does not match the program configuration.

Put the dedicated Solana publication keypair in `secrets/publication-authority.json`. The container runs as UID/GID `10001`, so that user must be able to traverse `secrets/` and read the file; on a Linux VPS, for example:

```sh
sudo chown 10001:10001 secrets secrets/publication-authority.json
chmod 750 secrets
chmod 600 secrets/publication-authority.json
```

For scheduled pool settlement, also place a **different**, SOL-funded keypair in `secrets/crank.json`, give it the same ownership and permissions, and uncomment `CRANK_KEYPAIR` in `.env.prod`. Without it, the HTTP service starts but the built-in settlement worker is disabled.

Start and check the stack:

```sh
docker compose --env-file .env.prod -f compose.prod.yaml config --quiet
docker compose --env-file .env.prod -f compose.prod.yaml up -d --build --wait
curl -fsS http://127.0.0.1:8080/readyz
```

The private `postgres_data` volume holds the database. Back it up together with the R2 objects, publication keypair, and encryption master key, then test restoring them together. The PostgreSQL TLS CA is stored in Docker volumes and lasts ten years; plan certificate rotation before expiry. This Compose deployment has one database instance, so it does not provide the replication and automated backups of the Helm deployment.

If Traefik runs directly on the VPS, it can proxy to `127.0.0.1:8080`. If Traefik runs in another Docker container, host loopback is not its loopback: attach **only the keyserver** to Traefik's existing external Docker network with a local Compose override, and keep PostgreSQL on its private network. Do not change the published port to `0.0.0.0`. Configure the proxy to preserve the public HTTPS origin and forward client IPs correctly; set `TRUSTED_PROXY_CIDRS` only to the actual proxy subnet. Set GitHub's `MOMENT_DEVNET_BACKEND_URL` to the same public origin as `AUTH_ORIGIN` before tagging an APK release.

## Tests

Run from `keyserver/`. Database tests require a disposable PostgreSQL database; each test uses an isolated schema.

```sh
docker compose -f compose.test.yaml up -d --wait
export TEST_DATABASE_URL='postgres://moment:moment-local-test@127.0.0.1:55432/moment_test?sslmode=disable'
cargo test --locked
```

The shared Android contract tests can also run without PostgreSQL:

```sh
cargo test --locked --test android_contract
```

For the real model test, install ONNX Runtime 1.23.2 and set `ORT_DYLIB_PATH`:

```sh
cargo test --locked real_model_matches_android_golden -- --ignored
```

The normal suite uses local RPC and object-store test servers. Numerical fixtures verify preprocessing and inference parity; they do not establish moderation accuracy or production calibration. Validation against a real R2 bucket and two physical devnet wallets remains necessary.

## Docker and Kubernetes

Run from the repository root:

```sh
docker buildx build --load -f keyserver/Dockerfile -t moment-keyserver:0.1.0 .
python3 keyserver/tests/container_smoke.py --image moment-keyserver:0.1.0
helm lint keyserver/helm/moment-keyserver --strict
```

The Docker context excludes local credentials and Android build outputs. The image includes the model and native runtime, runs as UID/GID 10001, and supports a read-only root filesystem.

The [Helm chart README](helm/moment-keyserver/README.md) covers existing secrets, CloudNativePG, TLS, R2 backups, external PostgreSQL, monitoring, and recovery. The chart does not install the required operators or CRDs. Local tests do not deploy infrastructure to a remote cluster.

Session tokens expire after 15 minutes, matching the wallet-signed challenge. The session-duration migration revokes existing tokens once; users reconnect after upgrading.
