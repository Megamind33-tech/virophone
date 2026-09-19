#!/usr/bin/env bash
# Render coturn + LiveKit configs from .env.vps (run on VPS or locally before deploy).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${VIRO_ENV_FILE:-$ROOT/.env.vps}"

if [[ ! -f "$ENV_FILE" ]]; then
  echo "Missing $ENV_FILE — copy .env.vps.example to .env.vps first." >&2
  exit 1
fi

set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

: "${TURN_SECRET:?Set TURN_SECRET in .env.vps}"
: "${TURN_REALM:?Set TURN_REALM in .env.vps}"
: "${EXTERNAL_IP:?Set EXTERNAL_IP in .env.vps (VPS public IPv4)}"

TEMPLATE="$ROOT/infra/coturn/turnserver.vps.conf.template"
OUT="$ROOT/infra/coturn/turnserver.vps.conf"

sed \
  -e "s|__TURN_SECRET__|${TURN_SECRET}|g" \
  -e "s|__TURN_REALM__|${TURN_REALM}|g" \
  -e "s|__EXTERNAL_IP__|${EXTERNAL_IP}|g" \
  "$TEMPLATE" > "$OUT"

echo "Rendered $OUT"

# --- LiveKit ---------------------------------------------------------------
# The key/secret here MUST be the same pair the API signs join tokens with
# (LIVEKIT_API_KEY/LIVEKIT_API_SECRET in .env.vps). Rendering both from one
# source is what makes a mismatch impossible.
: "${LIVEKIT_API_KEY:?Set LIVEKIT_API_KEY in .env.vps}"
: "${LIVEKIT_API_SECRET:?Set LIVEKIT_API_SECRET in .env.vps}"
: "${CADDY_DOMAIN:?Set CADDY_DOMAIN in .env.vps}"

if [[ ${#LIVEKIT_API_SECRET} -lt 32 ]]; then
  echo "LIVEKIT_API_SECRET must be at least 32 characters." >&2
  exit 1
fi

LK_TEMPLATE="$ROOT/infra/livekit/livekit.vps.yaml.template"
LK_OUT="$ROOT/infra/livekit/livekit.vps.yaml"

sed \
  -e "s|__LIVEKIT_API_KEY__|${LIVEKIT_API_KEY}|g" \
  -e "s|__LIVEKIT_API_SECRET__|${LIVEKIT_API_SECRET}|g" \
  "$LK_TEMPLATE" > "$LK_OUT"

# TURNS (TURN-over-TLS on 5349) is the fallback for networks that block UDP
# outright. It needs a real cert for CADDY_DOMAIN. LiveKit refuses to start
# when turn.enabled is set and the files are missing — which presents as a
# crash-looping container and calls with no audio — so the block is only
# appended when the cert pair actually exists.
TLS_DIR="$ROOT/livekit-tls"
if [[ -s "$TLS_DIR/fullchain.pem" && -s "$TLS_DIR/privkey.pem" ]]; then
  cat >> "$LK_OUT" <<TURNBLOCK
turn:
  enabled: true
  domain: ${CADDY_DOMAIN}
  cert_file: /etc/livekit/tls/fullchain.pem
  key_file: /etc/livekit/tls/privkey.pem
  tls_port: 5349
  relay_range_start: 30000
  relay_range_end: 30100
TURNBLOCK
  echo "Rendered $LK_OUT (TURNS enabled)"
else
  mkdir -p "$TLS_DIR"
  echo "Rendered $LK_OUT (TURNS DISABLED — no cert pair in livekit-tls/)"
  echo "  Media still works over UDP 51000-51100 and ICE/TCP 7981, plus coturn on ${VIRO_TURN_PORT:-3479}."
  echo "  To enable TURNS, place fullchain.pem + privkey.pem for ${CADDY_DOMAIN} in livekit-tls/ and re-run."
fi
