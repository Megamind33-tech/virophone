#!/usr/bin/env bash
# Build the Android app and push it to testers via Firebase App Distribution.
#
# This exists because installing over USB is unreliable on the Windows dev
# machine (ADB and MTP both report driver status "Unknown"), which meant
# fixes were being written but never actually reaching a phone. Testers get
# an email with an install link instead; no cable involved.
#
#   ./scripts/distribute-android.sh                      # notes from last commit
#   ./scripts/distribute-android.sh "what changed"       # explicit notes
#   VIRO_TESTER_GROUP=round-one ./scripts/distribute-android.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID_DIR="$ROOT/apps/android"
# The build now produces one APK per architecture rather than one holding
# both, so the file to upload has an ABI in its name. arm64 is every phone
# made for roughly the last decade; VIRO_ABI=armeabi-v7a builds and ships the
# older one for a tester who needs it.
VIRO_ABI="${VIRO_ABI:-arm64-v8a}"
APK="$ANDROID_DIR/app/build/outputs/apk/debug/app-${VIRO_ABI}-debug.apk"

# From apps/android/app/google-services.json (mobilesdk_app_id). That file is
# gitignored, so the id is kept here rather than read out of it.
APP_ID="${VIRO_FIREBASE_APP_ID:-1:74644641402:android:57579c43fba4ecbde98dc3}"
PROJECT="${VIRO_FIREBASE_PROJECT:-viro-8a}"

# Either a group alias (preferred once there's more than a couple of testers)
# or a comma-separated list of addresses.
# Everyone in the "Viro Testers" group (alias: testers) gets every build.
# It used to go to one address — the owner — so nobody else was ever assigned
# a release, and Firebase will not show or offer a download to anybody who has
# not been. People join the group themselves through an invite link made in
# the Firebase console; after that every new build reaches them on its own.
TESTER_GROUP="${VIRO_TESTER_GROUP:-testers}"
TESTERS="${VIRO_TESTERS:-chansamosty11@gmail.com}"

NOTES="${1:-$(git -C "$ROOT" log -1 --pretty=%s)}"

# The firebase CLI is not on PATH under Git Bash on this machine (npm installs
# it as a .cmd shim), and Node stalls ~15s on an IPv6 connect here before
# falling back to IPv4 — longer than the CLI's own 10s timeout, so every call
# failed with a connect error that looked like an outage. Both are worked around
# rather than requiring the operator to remember them.
FIREBASE_BIN="${VIRO_FIREBASE_BIN:-}"
if [[ -z "$FIREBASE_BIN" ]]; then
  if command -v firebase >/dev/null 2>&1; then
    FIREBASE_BIN="firebase"
  elif [[ -x "$HOME/AppData/Roaming/npm/firebase.cmd" ]]; then
    FIREBASE_BIN="$HOME/AppData/Roaming/npm/firebase.cmd"
  else
    echo "firebase CLI not found. Install it with: npm install -g firebase-tools" >&2
    exit 1
  fi
fi
export NODE_OPTIONS="${NODE_OPTIONS:---dns-result-order=ipv4first}"

echo "==> Building debug APK"
(cd "$ANDROID_DIR" && ./gradlew :app:assembleDebug -q)

if [[ ! -f "$APK" ]]; then
  echo "Build reported success but $APK is missing." >&2
  exit 1
fi

echo "==> Uploading $(du -h "$APK" | cut -f1) to Firebase App Distribution"
if [[ -n "$TESTER_GROUP" ]]; then
  "$FIREBASE_BIN" appdistribution:distribute "$APK" \
    --app "$APP_ID" --project "$PROJECT" \
    --release-notes "$NOTES" --groups "$TESTER_GROUP"
else
  "$FIREBASE_BIN" appdistribution:distribute "$APK" \
    --app "$APP_ID" --project "$PROJECT" \
    --release-notes "$NOTES" --testers "$TESTERS"
fi

echo "==> Done. Testers get an email; installs need 'unknown sources' allowed once."
