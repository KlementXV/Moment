#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APK="$ROOT_DIR/app/app/build/outputs/apk/debug/app-debug.apk"
SERIAL="${ANDROID_SERIAL:-}"
BUILD=true
LAUNCH=true

usage() {
    cat <<'USAGE'
Usage: ./scripts/push-seeker.sh [--serial SERIAL] [--no-build] [--no-launch]

Compile Moment en debug, l’installe sur le Seeker et ouvre l’application.
  --serial SERIAL  Choisir explicitement l’appareil (ou définir ANDROID_SERIAL).
  --no-build       Installer l’APK debug existant sans recompiler.
  --no-launch      Installer sans ouvrir l’application.
  -h, --help       Afficher cette aide.

Prérequis : débogage USB activé, câble USB et autorisation ADB acceptée.
L’installation conserve les données de l’application existante.
USAGE
}

fail() { printf 'Erreur : %s\n' "$*" >&2; exit 1; }

while [[ $# -gt 0 ]]; do
    case "$1" in
        --serial)
            [[ $# -ge 2 && -n "$2" && "$2" != --* ]] || fail "--serial exige un identifiant."
            SERIAL="$2"
            shift 2
            ;;
        --no-build) BUILD=false; shift ;;
        --no-launch) LAUNCH=false; shift ;;
        -h|--help) usage; exit 0 ;;
        *) usage >&2; fail "Option inconnue : $1" ;;
    esac
done

ADB="$(command -v adb || true)"
if [[ -z "$ADB" ]]; then
    LOCAL_SDK=""
    if [[ -f "$ROOT_DIR/app/local.properties" ]]; then
        LOCAL_SDK="$(sed -n 's/^sdk\.dir=//p' "$ROOT_DIR/app/local.properties" | head -n 1)"
    fi
    for SDK in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$LOCAL_SDK" "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
        if [[ -n "$SDK" && -x "$SDK/platform-tools/adb" ]]; then
            ADB="$SDK/platform-tools/adb"
            break
        fi
    done
fi
[[ -n "$ADB" ]] || fail "ADB introuvable. Installe Android SDK Platform Tools ou configure ANDROID_HOME."

DEVICES="$("$ADB" devices)"
if [[ -z "$SERIAL" ]]; then
    SEEKERS=()
    while read -r DEVICE STATUS REST; do
        [[ "$STATUS" == device && "$DEVICE" != emulator-* ]] || continue
        MODEL="$("$ADB" -s "$DEVICE" shell getprop ro.product.model | tr -d '\r')"
        if [[ "$MODEL" == *Seeker* || "$MODEL" == *seeker* ]]; then
            SEEKERS+=("$DEVICE")
        fi
    done <<< "$DEVICES"
    if [[ ${#SEEKERS[@]} -ne 1 ]]; then
        printf '%s\n' "$DEVICES" >&2
        fail "Il faut un Seeker autorisé connecté. Déverrouille-le et accepte l’autorisation USB ; si plusieurs appareils sont présents, utilise --serial SERIAL."
    fi
    SERIAL="${SEEKERS[0]}"
fi

STATUS="$("$ADB" -s "$SERIAL" get-state 2>/dev/null || true)"
[[ "$STATUS" == device ]] || fail "Appareil $SERIAL indisponible ou non autorisé. Vérifie le câble et accepte l’autorisation USB sur le téléphone."

if "$BUILD"; then
    if [[ -z "${JAVA_HOME:-}" && -d '/Applications/Android Studio.app/Contents/jbr/Contents/Home' ]]; then
        export JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
    fi
    printf 'Compilation de Moment…\n'
    (cd "$ROOT_DIR/app" && ./gradlew :app:assembleDebug)
fi
[[ -f "$APK" ]] || fail "APK absent : $APK. Relance sans --no-build."

printf 'Installation sur %s…\n' "$SERIAL"
"$ADB" -s "$SERIAL" install -r "$APK"
if "$LAUNCH"; then
    "$ADB" -s "$SERIAL" shell am start -W -n com.clockin.hackathon/.MainActivity
fi
printf 'Moment installé sur %s.\n' "$SERIAL"
