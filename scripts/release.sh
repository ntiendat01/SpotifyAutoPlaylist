#!/usr/bin/env bash
# TASK-054: build, verify and record a signed release APK. Prints no secrets.
# Needs: spotify.local.properties (login), signing via REPLAY_SIGNING_* env or
# ~/.replay-signing/signing.properties (or -Preplay.signingProperties=<path> in GRADLE_ARGS).
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ANDROID_HOME:?set ANDROID_HOME}"
BT="${BUILD_TOOLS:-$ANDROID_HOME/build-tools/35.0.0}"
[ -f spotify.local.properties ] || { echo "spotify.local.properties missing: release would have login disabled" >&2; exit 1; }
git diff --quiet && git diff --cached --quiet || { echo "working tree not clean" >&2; exit 1; }
./gradlew --no-daemon ${GRADLE_ARGS:-} :app:assembleRelease
APK=app/build/outputs/apk/release/app-release.apk
[ -f "$APK" ] || { echo "no signed app-release.apk (signing not configured?)" >&2; exit 1; }
"$BT/apksigner" verify --verbose --print-certs "$APK" | grep -E "Verified using|certificate SHA-256|Number of signers"
VER=$(grep '^replay.versionName=' gradle.properties | cut -d= -f2)
OUT="dist/replay-$VER"; mkdir -p "$OUT"
cp "$APK" "$OUT/replay-$VER.apk"
( cd "$OUT" && sha256sum "replay-$VER.apk" > "replay-$VER.apk.sha256" )
"$BT/aapt2" dump badging "$APK" | head -1 | sed "s/ platformBuild.*//" > "$OUT/badging.txt"
grep -q "debuggable" <("$BT/aapt2" dump badging "$APK") && { echo "release is debuggable!" >&2; exit 1; }
git rev-parse HEAD > "$OUT/source-commit.txt"
echo "Recorded in $OUT:"; cat "$OUT/replay-$VER.apk.sha256" "$OUT/badging.txt" "$OUT/source-commit.txt"
echo "Fill docs/releases/TEMPLATE.md -> docs/releases/v$VER.md. Keep $OUT (rollback APK) outside git."
