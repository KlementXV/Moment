# Tests and continuous integration

The repository contains tests for wallet signatures, transaction encoding, account layouts, pool accounting, encrypted storage, publication retries, backend authorization, photo preprocessing, and Android UI flows.

Android unit tests and lint:

```sh
cd app
./gradlew :app:testDevnetDebugUnitTest :app:testMainnetDebugUnitTest :app:lintDevnetDebug
```

Android instrumented tests, with a device or emulator connected:

```sh
cd app
./gradlew :app:connectedDevnetDebugAndroidTest
```

Program tests:

```sh
cd program
anchor build --arch v0
cargo test -p moment
```

Backend tests:

```sh
cd keyserver
cargo test --locked
```

Backend database integration tests use `TEST_DATABASE_URL` for a dedicated PostgreSQL test database. Native moderation tests also require the ONNX Runtime library configured through `ORT_DYLIB_PATH`.

The [CI workflow](../.github/workflows/ci.yml) builds the program, runs Rust checks and tests, validates the Helm chart, and builds the devnet Android variant. A debug devnet APK from branch and pull-request runs is uploaded as a workflow artifact. Tags build a signed devnet APK for GitHub Releases. The mainnet variant is excluded from CI until its deployment is ready. After the backend and Helm jobs pass, pushes can also publish the keyserver image to GitHub Container Registry.

## GitHub Actions configuration

In the repository's **Settings → Secrets and variables → Actions**, configure the values consumed by the workflow:

| Type | Name | Purpose |
| --- | --- | --- |
| Variable | `MOMENT_DEVNET_BACKEND_URL` | HTTPS URL of the deployed devnet keyserver |
| Variable | `MOMENT_DEVNET_PROGRAM_ID` | Optional devnet program address; the app has a committed devnet default |
| Secret | `ANDROID_KEYSTORE_BASE64` | Base64-encoded release keystore; required for tagged releases |
| Secret | `ANDROID_KEYSTORE_PASSWORD` | Keystore password; required for tagged releases |
| Secret | `ANDROID_KEY_ALIAS` | Release key alias; required for tagged releases |
| Secret | `ANDROID_KEY_PASSWORD` | Release key password; required for tagged releases |

The devnet APK can build without a backend URL, but real publication and feed access require a configured backend. If `MOMENT_DEVNET_PROGRAM_ID` is unset, Gradle uses the committed devnet default. Program IDs are public addresses, so GitHub Variables are appropriate. No mainnet settings are currently needed in GitHub Actions.

RPC and backend URLs are embedded in the APK. Storing an RPC URL in a GitHub Secret does not keep a provider API key private once the APK is distributed.

GitHub supplies `GITHUB_TOKEN` automatically for GHCR publication. The workflow builds and publishes the backend image; it does not deploy the server. R2 credentials, database passwords, Solana private keypairs, and `KEY_ENCRYPTION_KEY` belong in backend deployment secrets and are not required by this CI workflow.

## APK releases

Pushing a stable SemVer tag such as `v0.3.0` publishes a GitHub Release after program, backend, Helm, and Android checks pass. Gradle derives `versionName` and `versionCode` from the tag: `v0.3.0` becomes `0.3.0` and `3000`. Local builds without `-PversionTag` use `v0.3.0` as a development fallback. The release contains one signed devnet APK and `SHA256SUMS.txt`; release uploads go directly to GitHub Releases without using Actions artifacts. Image publication runs independently.

For each new version:

1. Commit and push the intended release changes, including the workflow configuration.
2. Create and push a new, higher version tag from that commit:

   ```sh
   git tag v0.3.0
   git push origin v0.3.0
   ```

3. Wait for CI; the release is published automatically with generated notes and the devnet APK.

Only stable `vMAJOR.MINOR.PATCH` tags are supported. Minor and patch must each be at most 999, and the computed Android code must be within 1–2,100,000,000. Pre-release tags are rejected because two release candidates could otherwise share a `versionCode`. Use strictly increasing tags for installable Android updates. Re-running the tag job can replace assets in the draft, but refuses to replace assets in an already published release.

Create the release keystore and the four GitHub secret values once:

```sh
./scripts/create-android-release-key.sh
```

The script writes `keys/android-release/release.jks` and `keys/android-release/github-secrets.env` with restrictive permissions. The whole `keys/` directory is ignored by Git. Add the four values from the secrets file in **Settings → Secrets and variables → Actions**, or run the script with `--github` on the first invocation to upload them to the current repository using an authenticated GitHub CLI. The script refuses to overwrite an existing key; if you already generated one, use `gh secret set --env-file keys/android-release/github-secrets.env` to upload its values. Back up the keystore and both passwords securely outside this working tree. Never commit or print the secrets file.

The tagged build refuses to publish if a signing secret is missing. A previously installed debug APK cannot be updated by a release-signed APK; uninstalling it removes local app data. Losing the release key prevents further installable updates under the same application ID.

## Troubleshooting image publication

If the image builds successfully but GHCR rejects the push with `permission_denied: read_package`, check the package's **Package settings → Manage Actions access**. Add `KlementXV/Moment` with the **Write** role, or verify that its existing role permits publication. The workflow already requests `packages: write`; that permission does not replace the package's access rules. Linking an existing package to a repository alone does not necessarily grant Actions access.

See [GitHub's package access documentation](https://docs.github.com/en/packages/learn-github-packages/configuring-a-packages-access-control-and-visibility).

Docker build-record artifact uploads are disabled to reduce artifact storage use; the build summary remains enabled. If GitHub reports an artifact storage quota error, remove unneeded artifacts or adjust billing/storage capacity. Disabling build records does not free existing storage or resolve APK artifact uploads while the quota remains exhausted. The failure log notes that usage is recalculated every 6–12 hours.

[Back to the project overview](../README.md).
