# Moment

**A daily photo ritual for the Seeker community, powered by Solana.**

Built for the **Solana Mobile Clock In Hackathon**, Moment is a native Android app that connects everyday participation to a shared SKR pool. Capture your surroundings, take a selfie, and publish your daily Moment to unlock the community feed. Your wallet signs the publication, and a Solana program records your check-in and manages the pool.

## The experience

Moment gives the community a reason to show up together each day:

1. **Connect your wallet.** Create your on-chain profile through Mobile Wallet Adapter.
2. **Stake SKR.** Put a stake into the program's token vault to participate.
3. **Capture your Moment.** Take a rear-camera photo followed by a selfie, directly in the app.
4. **Publish and unlock the feed.** Review both photos, sign the publication, and see today's Moments from the community.
5. **Keep the ritual going.** Track your streak, manage your stake, and return the next day.

The app supports English and French. Publication days follow UTC; deadlines are displayed in the phone's local time zone.

### The daily pool

The devnet configuration uses the following rules:

| Rule | Value |
| --- | --- |
| Minimum stake | 500 test SKR |
| Missed-day penalty | 10% of the remaining stake per missed day |
| Pool distribution | Proportional to each publisher's stake for that day |
| Pool closure | 06:00 UTC on the following day |
| Withdrawal delay | 48 hours |
| Devnet faucet | 1,000 test SKR |

Missed-day penalties fund the daily pool. Participants who publish that day can receive their share after the pool closes. Settlement and payouts are enforced by the Solana program; the backend's scheduled worker submits the transactions. Users can request or cancel a withdrawal from their profile.

These parameters belong to the program configuration. Devnet uses a test mint; its tokens have no monetary value.

## Solana Mobile integration

- **Native Android:** Kotlin and Jetpack Compose, designed for Seeker.
- **Wallet signing:** Mobile Wallet Adapter handles wallet authorization, message signing, and transaction signing. User private keys stay in the wallet.
- **On-chain participation:** An Anchor program manages profiles, stakes, daily check-ins, pools, penalties, and withdrawals.
- **Camera capture:** CameraX captures the rear photo and selfie sequentially, with review and retake controls.
- **Wallet identity:** The profile can resolve eligible `.skr` names owned by the connected wallet on mainnet. A local display name remains available.
- **Local photo analysis:** The bundled Marqo NSFW model runs through ONNX Runtime during photo review; the backend independently checks both photos before authorizing publication.

## Architecture

```text
Android app ── Mobile Wallet Adapter ── Wallet
    │                                    │
    │ authenticated requests             │ signed transactions
    ▼                                    ▼
Rust keyserver ── publication cosignature ── Solana / Anchor
    │
    ├── PostgreSQL: sessions, posts, encrypted photo keys
    └── Private R2 bucket: encrypted photo packets
```

The wallet signs a canonical `moment-manifest-v1` manifest describing the photos. Its SHA-256 hash becomes the on-chain commitment. The app encrypts the photo packet, submits it to the keyserver, and obtains the publication authority's cosignature for `check_in`. The backend confirms the transaction before exposing the post in the feed.

On-chain accounts hold the participation state and photo commitments. Photos are stored off-chain as encrypted packets. The backend checks a reader's on-chain eligibility before serving content; the app verifies the packet hash, manifest, wallet signature, and encryption integrity before displaying it.

### Privacy and trust

Photos are re-encoded as JPEGs after orientation correction, cropping, and resizing to a maximum edge of 1,280 pixels. Metadata is removed before publication. Capture drafts and pending submissions are encrypted locally with keys held in Android Keystore, allowing an interrupted publication to resume.

The keyserver is a trusted service: it can decrypt photos for moderation and control access to photo keys. R2 stores encrypted packets and receives no decryption keys. The app contains neither R2 credentials nor the publication authority's private key. Captions travel inside the encrypted packet and are outside the wallet-signed photo manifest.

## Project status

**Version 0.3.1 — devnet prototype.**

The repository includes the Android app, Anchor program, Rust backend, Docker image definition, and Kubernetes Helm chart. Wallet transactions, camera capture, encrypted publication, backend authorization, and feed retrieval are implemented.

The current Android devnet defaults are:

| Setting | Value |
| --- | --- |
| Program ID | `ANT4AF24p1io1pmdNFKd9RKMStLCFi6WGbu81QoGzqN6` |
| Test SKR mint | `FKu7X2R2WVXDTaAQb6eE7xDtBJss9Yp3fYmBf6YyYAa5` |
| Test mint decimals | 9 |
| Keyserver | `https://moment-dev.noodler.cc` |

The backend URL must be configured to publish and retrieve real Moments. A complete run with two physical wallets and a deployed R2 backend remains to be validated. Moderation thresholds are experimental and require calibration before production publication. The mainnet build variant is included, but a production deployment is still pending.

## Run the Android app

### Prerequisites

- Android Studio, Android SDK 37, and JDK 17 or a compatible Android Studio JBR.
- An Android device running Android 8.0 or later; Seeker is the target device.
- A wallet compatible with Mobile Wallet Adapter, with devnet SOL for transaction fees.
- A running devnet keyserver whose publication authority matches the program configuration, for real publication and feed access.

### Configure

Open `app/` in Android Studio. Add the following settings to `app/local.properties`, alongside the SDK path created by Android Studio:

```properties
moment.devnet.rpcUrl=https://api.devnet.solana.com
moment.devnet.programId=ANT4AF24p1io1pmdNFKd9RKMStLCFi6WGbu81QoGzqN6
moment.devnet.skrMint=FKu7X2R2WVXDTaAQb6eE7xDtBJss9Yp3fYmBf6YyYAa5
moment.devnet.skrDecimals=9
moment.devnet.backendUrl=https://moment-dev.noodler.cc
```

`https://moment-dev.noodler.cc` is the public devnet keyserver used with the program and test mint above. If you deploy your own keyserver, program, or test mint, replace those values. `local.properties` is ignored by Git.

Settings can also be supplied through environment variables such as `MOMENT_DEVNET_BACKEND_URL` and `MOMENT_DEVNET_RPC_URL`. Network-specific properties take precedence over environment variables. The app reads the mint's actual decimals on-chain before allowing staking.

### Build and install

```sh
cd app
./gradlew :app:assembleDevnetDebug
```

The APK is written to `app/app/build/outputs/apk/devnet/debug/app-devnet-debug.apk`, relative to the repository root.

To build, install, and launch on a USB-connected Seeker, enable USB debugging and run from the repository root:

```sh
./scripts/push-seeker.sh
```

Use `--no-build` to install an existing APK, `--no-launch` to install without opening the app, or `--serial SERIAL` to select a device.

| Variant | App name | Application ID | Build task |
| --- | --- | --- | --- |
| Devnet | Moment dev | `com.klementxv.moment.dev` | `:app:assembleDevnetDebug` |
| Mainnet | Moment | `com.klementxv.moment` | `:app:assembleMainnetDebug` |

The variants install side by side. Mainnet settings use the `moment.mainnet.*` properties or `MOMENT_MAINNET_*` environment variables. Configure a deployed mainnet program and backend before using that variant.

### Demo walkthrough

1. Open **Moment dev**, complete onboarding, and connect your wallet.
2. Open your profile, claim test SKR from the devnet faucet, and stake at least 500 test SKR.
3. Select **Capture my moment**, allow camera access, take the rear photo, then the selfie.
4. Review the pair and publish. Approve the wallet prompts for authentication, the manifest, and the transaction as requested.
5. After confirmation, browse the unlocked community feed and inspect your updated profile.
6. Try requesting and cancelling a withdrawal to explore the stake lifecycle.

For an interface preview, the profile also provides an optional demo feed with illustrated sample Moments. Those samples do not represent real publications or on-chain activity.

## Development documentation

- [Backend and Anchor program setup](docs/backend-and-program.md)
- [Tests, continuous integration, and GitHub Actions variables](docs/testing-and-ci.md)
- [Keyserver configuration and API](keyserver/README.md)
- [Kubernetes deployment](keyserver/helm/moment-keyserver/README.md)

The CI builds and tests the program and backend, validates the Helm chart, and builds the devnet Android variant. Manual workflow runs provide a debug APK as a workflow artifact; version tags publish a signed devnet APK in GitHub Releases. The mainnet variant remains available for local builds but is excluded from CI until its deployment is ready. CI does not publish the keyserver image; [build it with Docker](keyserver/README.md#docker-image).

## Repository layout

| Path | Contents |
| --- | --- |
| [`app/`](app/) | Native Android app, resources, bundled moderation model, and tests |
| [`program/`](program/) | Anchor program, LiteSVM tests, and deployment tools |
| [`keyserver/`](keyserver/) | Publication backend, SQL migrations, Docker configuration, and Helm chart |
| [`scripts/`](scripts/) | Seeker installation and moderation model export/calibration tools |

## License

Moment is licensed under [Apache-2.0](LICENSE). See [NOTICE](NOTICE) for attribution.

The bundled `Marqo/nsfw-image-detection-384` moderation model is also licensed under Apache-2.0 and includes its own [license](app/app/src/main/assets/moderation/LICENSE.txt) and [notice](app/app/src/main/assets/moderation/NOTICE.txt).
