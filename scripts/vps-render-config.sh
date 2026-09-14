#!/usr/bin/env bash
# Render coturn config from .env.vps (run on VPS or locally before deploy).
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
