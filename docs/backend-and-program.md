# Backend and program setup

Commands below run from the repository root unless a `cd` is shown.

### Keyserver

The backend uses Rust, Axum, PostgreSQL 17, ONNX Runtime CPU 1.23.2, and either local blob storage or a private Cloudflare R2 bucket.

```sh
cd keyserver
cargo build --locked --release
mkdir -p data secrets
chmod 700 data secrets
cp .env.example .env
chmod 600 .env
```

Configure `.env` with the database connection, RPC endpoint, publication authority keypair, encryption key, storage settings, and native ONNX Runtime library path. The authority must match the program's `Config.publication_authority`. The server does not automatically load `.env`; source it before starting:

```sh
set -a
. ./.env
set +a
./target/release/moment-keyserver
```

For local devnet development, `BLOB_STORE=local` enables local encrypted storage. The experimental moderation policy requires the explicit devnet-only setting `ALLOW_UNCALIBRATED_MODERATION=true`; the backend rejects that setting on mainnet.

Detailed configuration and API documentation are in the [keyserver README](../keyserver/README.md). Kubernetes deployment is documented in the [Helm chart README](../keyserver/helm/moment-keyserver/README.md).

### Anchor program

The program uses four account types:

| Account | Purpose |
| --- | --- |
| `Config` | Token mint, publication authority, and economic parameters |
| `Profile` | Wallet participation, stake, streak, withdrawal state, and pending pool claims |
| `DayPool` | Daily stake totals and penalties available for distribution |
| `CheckIn` | Wallet/day publication commitment and encrypted packet reference |

Build with the SBPF v0 target used by the repository's LiteSVM tests:

```sh
cd program
anchor build --arch v0
```

Initial configuration must be signed by the program’s current upgrade authority, before making the program immutable. The bootstrap instruction includes the program-data account to enforce this on-chain.

The [devnet deployment script](../program/scripts/deploy-devnet.sh) builds and deploys the program, creates a test mint, initializes the configuration, seeds the pool, and transfers test-mint authority to the program. It requires the Solana, SPL Token, and Anchor CLIs, a funded devnet admin wallet, and the bootstrap dependencies:

```sh
cd program/scripts
npm ci
cd ../..
PUBLICATION_AUTHORITY=YOUR_PUBLICATION_AUTHORITY_PUBLIC_KEY ./program/scripts/deploy-devnet.sh
```

Use the resulting program and mint addresses in both the app and keyserver configuration.

[Back to the project overview](../README.md).
