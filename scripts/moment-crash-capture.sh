#!/usr/bin/env bash
# Capture what a Moment crash actually was, from the phone.
#
# Everything the reliability work needs and this machine cannot produce on its
# own: the app dies below the JVM, so the evidence is in the system log and the
# tombstone rather than in anything Viro can write about itself.
#
# Plug the phone in, allow USB debugging, then:
#
#   ./scripts/moment-crash-capture.sh            # watch, then reproduce
#   ./scripts/moment-crash-capture.sh --meminfo  # one memory sample, no watching
#
# Reproduce while it runs: Now -> Start a Moment -> mood -> room -> leave,
# several times, then open Start a Moment again.
set -uo pipefail

PKG="${VIRO_PACKAGE:-com.viroreach.app}"
OUT="${1:-}"
STAMP="$(date +%Y%m%d-%H%M%S)"
DIR="${VIRO_CAPTURE_DIR:-./crash-capture-$STAMP}"
ADB="${ADB:-adb}"

if ! "$ADB" get-state >/dev/null 2>&1; then
  echo "No device. Plug the phone in, allow USB debugging, and check 'adb devices'." >&2
  exit 1
fi

mkdir -p "$DIR"
echo "==> Capturing into $DIR (device: $("$ADB" shell getprop ro.product.model | tr -d '\r'))"

mem() {
  local label="$1"
  {
    echo "### $label — $(date -Is)"
    "$ADB" shell dumpsys meminfo "$PKG"
  } >> "$DIR/meminfo.txt" 2>&1
  # The lines the requirement is actually about.
  "$ADB" shell dumpsys meminfo "$PKG" 2>/dev/null |
    grep -E "Native Heap|Java Heap|Graphics|GL mtrack|TOTAL PSS|TOTAL RSS" |
    sed "s/^/  [$label] /"
}

if [[ "$OUT" == "--meminfo" ]]; then
  mem "sample"
  echo "==> Written to $DIR/meminfo.txt"
  exit 0
fi

mem "before"

echo "==> Clearing the log and watching. Reproduce the crash now; Ctrl-C when the app dies."
"$ADB" logcat -c 2>/dev/null || true
"$ADB" logcat -v threadtime > "$DIR/logcat-threadtime.txt" 2>&1 &
WATCH=$!
trap 'kill "$WATCH" 2>/dev/null || true' EXIT INT TERM
wait "$WATCH" 2>/dev/null || true

echo
echo "==> Collecting the aftermath"
"$ADB" logcat -b crash -d            > "$DIR/logcat-crash.txt"     2>&1 || true
"$ADB" shell dumpsys activity processes > "$DIR/activity-processes.txt" 2>&1 || true
mem "after"
# Tombstones need root on most retail phones; absence is not evidence of health.
"$ADB" shell ls -t /data/tombstones 2>/dev/null | head -5 > "$DIR/tombstones-list.txt" || true

echo
echo "==> What the log says about a process-level death:"
grep -nE "Fatal signal|SIGSEGV|SIGABRT|SIGBUS|tombstoned|crash_dump|RenderThread|Skia|OpenGLRenderer|Vulkan|EGL|AudioRecord|AudioTrack|AudioFlinger|MediaCodec|OutOfMemory|lmkd|lowmemorykiller|Killing|libwebrtc|rive" \
  "$DIR/logcat-threadtime.txt" "$DIR/logcat-crash.txt" 2>/dev/null | head -60 ||
  echo "  (nothing matched — send the whole folder anyway)"

echo
echo "==> Done. Send the folder: $DIR"
