#!/usr/bin/env bash
set -euo pipefail

if [[ $# -gt 1 || ( $# -eq 1 && "$1" != "--github" ) ]]; then
  echo "Usage: $0 [--github]" >&2
  exit 2
fi

for command in keytool openssl base64; do
  command -v "$command" >/dev/null || { echo "Missing command: $command" >&2; exit 1; }
done

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
output_dir="$repo_root/keys/android-release"
keystore="$output_dir/release.jks"
secrets_file="$output_dir/github-secrets.env"

umask 077
mkdir -p "$output_dir"
chmod 700 "$output_dir"
if [[ -e "$keystore" || -e "$secrets_file" ]]; then
  echo "Release key material already exists in $output_dir; refusing to overwrite it." >&2
  exit 1
fi

temporary_dir="$(mktemp -d "$output_dir/.create.XXXXXX")"
trap 'rm -rf "$temporary_dir"' EXIT

export ANDROID_KEYSTORE_PASSWORD="$(openssl rand -hex 24)"
export ANDROID_KEY_PASSWORD="$(openssl rand -hex 24)"
ANDROID_KEY_ALIAS=release

keytool -genkeypair -keystore "$temporary_dir/release.jks" \
  -storetype JKS -alias "$ANDROID_KEY_ALIAS" -keyalg RSA -keysize 4096 \
  -validity 10000 -dname "CN=Moment Android Release" \
  -storepass:env ANDROID_KEYSTORE_PASSWORD \
  -keypass:env ANDROID_KEY_PASSWORD -noprompt

{
  printf 'ANDROID_KEYSTORE_BASE64=%s\n' "$(base64 < "$temporary_dir/release.jks" | tr -d '\r\n')"
  printf 'ANDROID_KEYSTORE_PASSWORD=%s\n' "$ANDROID_KEYSTORE_PASSWORD"
  printf 'ANDROID_KEY_ALIAS=%s\n' "$ANDROID_KEY_ALIAS"
  printf 'ANDROID_KEY_PASSWORD=%s\n' "$ANDROID_KEY_PASSWORD"
} > "$temporary_dir/github-secrets.env"

mv "$temporary_dir/release.jks" "$keystore"
mv "$temporary_dir/github-secrets.env" "$secrets_file"
chmod 600 "$keystore" "$secrets_file"

if [[ ${1:-} == --github ]]; then
  command -v gh >/dev/null || { echo "GitHub CLI (gh) is required for --github" >&2; exit 1; }
  (cd "$repo_root" && gh secret set --env-file "$secrets_file")
  echo "Android signing secrets uploaded to the current GitHub repository."
fi

echo "Keystore: $keystore"
echo "GitHub secret values: $secrets_file"
echo "Back up the keystore and passwords securely. Do not commit or print the secrets file."
