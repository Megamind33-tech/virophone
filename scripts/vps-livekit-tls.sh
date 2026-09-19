#!/usr/bin/env bash
# Run ON THE VPS. Copies the existing Caddy-issued certificate for
# CADDY_DOMAIN into livekit-tls/ so LiveKit can serve TURNS on 5349.
#
# TURNS is the last-resort media path for networks that block UDP outright
# (some corporate Wi-Fi and mobile carriers). Direct UDP and ICE/TCP cover
# most networks; this covers the rest. Re-run after each cert renewal, or
# add it to cron — Caddy renews every ~60 days and a stale cert here means
# TURNS stops working with no other symptom than calls failing on exactly
# those restrictive networks.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${VIRO_ENV_FILE:-$ROOT/.env.vps}"

set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

: "${CADDY_DOMAIN:?Set CADDY_DOMAIN in .env.vps}"

CADDY_STORE="${CADDY_CERT_STORE:-/var/lib/caddy/.local/share/caddy/certificates}"
CRT="$(find "$CADDY_STORE" -name "${CADDY_DOMAIN}.crt" 2>/dev/null | head -n1)"
KEY="$(find "$CADDY_STORE" -name "${CADDY_DOMAIN}.key" 2>/dev/null | head -n1)"

if [[ -z "$CRT" || -z "$KEY" ]]; then
  echo "No Caddy certificate found for ${CADDY_DOMAIN} under ${CADDY_STORE}." >&2
  echo "Set CADDY_CERT_STORE to the right path, or skip TURNS — calls still work" >&2
  echo "over UDP 51000-51100 and ICE/TCP 7981." >&2
  exit 1
fi

mkdir -p "$ROOT/livekit-tls"
install -m 0644 "$CRT" "$ROOT/livekit-tls/fullchain.pem"
install -m 0640 "$KEY" "$ROOT/livekit-tls/privkey.pem"
echo "Installed TURNS cert for ${CADDY_DOMAIN} into livekit-tls/"

"$ROOT/scripts/vps-render-config.sh"
docker compose -f "$ROOT/docker-compose.vps.yml" --env-file "$ENV_FILE" up -d livekit
echo "LiveKit restarted with TURNS enabled on 5349."
