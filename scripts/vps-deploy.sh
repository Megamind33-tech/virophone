#!/usr/bin/env bash
# Deploy Viro Reach to your VPS from your LOCAL PC (where Cursor runs).
# Keeps a SEPARATE install from your other app: /opt/viro-reach, project viro-reach, port 3479 TURN, API 127.0.0.1:13001.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${VIRO_ENV_FILE:-$ROOT/.env.vps}"

if [[ ! -f "$ENV_FILE" ]]; then
  echo "Create .env.vps from .env.vps.example first:" >&2
  echo "  cp .env.vps.example .env.vps && \$EDITOR .env.vps" >&2
  exit 1
fi

set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

: "${VIRO_VPS_HOST:?Set VIRO_VPS_HOST in .env.vps}"
: "${VIRO_VPS_USER:?Set VIRO_VPS_USER in .env.vps}"
: "${VIRO_VPS_PATH:?Set VIRO_VPS_PATH in .env.vps}"

SSH_OPTS=(-o BatchMode=yes -o StrictHostKeyChecking=accept-new)
if [[ -n "${VIRO_SSH_KEY:-}" ]]; then
  SSH_OPTS+=(-i "$VIRO_SSH_KEY")
fi

SSH=(ssh "${SSH_OPTS[@]}" "${VIRO_VPS_USER}@${VIRO_VPS_HOST}")
RSYNC_SSH="ssh ${SSH_OPTS[*]}"

echo "==> Rendering coturn config locally"
"$ROOT/scripts/vps-render-config.sh"

echo "==> Ensuring remote directory $VIRO_VPS_PATH"
"${SSH[@]}" "mkdir -p '$VIRO_VPS_PATH'"

echo "==> Syncing project (excluding node_modules, .git, Android build)"
rsync -az --delete \
  --exclude '.git' \
  --exclude 'node_modules' \
  --exclude 'apps/android' \
  --exclude '.env' \
  --exclude '.env.local' \
  -e "$RSYNC_SSH" \
  "$ROOT/" "${VIRO_VPS_USER}@${VIRO_VPS_HOST}:${VIRO_VPS_PATH}/"

echo "==> Copying .env.vps to remote as .env.vps"
rsync -az -e "$RSYNC_SSH" "$ENV_FILE" "${VIRO_VPS_USER}@${VIRO_VPS_HOST}:${VIRO_VPS_PATH}/.env.vps"

echo "==> Building and starting Docker (project: viro-reach)"
"${SSH[@]}" bash -s <<REMOTE
set -euo pipefail
cd '$VIRO_VPS_PATH'
set -a
source .env.vps
set +a
export COMPOSE_PROJECT_NAME=viro-reach
if ! command -v docker >/dev/null; then
  echo "Docker not found on VPS. Install Docker first." >&2
  exit 1
fi
docker compose -f docker-compose.vps.yml --env-file .env.vps build api
docker compose -f docker-compose.vps.yml --env-file .env.vps up -d postgres redis coturn api
if [[ "\${VIRO_CADDY_MODE:-existing}" == "container" ]]; then
  docker compose -f docker-compose.vps.yml --env-file .env.vps --profile viro-caddy up -d caddy
fi
docker compose -f docker-compose.vps.yml --env-file .env.vps ps
REMOTE

if [[ "${VIRO_CADDY_MODE:-existing}" == "existing" ]]; then
  echo ""
  echo "==> Host Caddy (existing): add reverse proxy for ${CADDY_DOMAIN:-your subdomain}"
  echo "    Snippet: infra/proxy/Caddyfile.host-snippet"
  echo "    Example on VPS:"
  echo "      sudo tee -a /etc/caddy/Caddyfile.d/viro-reach.caddy <<'EOF'"
  cat "$ROOT/infra/proxy/Caddyfile.host-snippet"
  echo "      EOF"
  echo "      sudo caddy reload --config /etc/caddy/Caddyfile"
fi

echo ""
echo "==> Health check (on VPS localhost)"
"${SSH[@]}" "curl -sf http://127.0.0.1:13001/health/live && curl -sf http://127.0.0.1:13001/health/ready && echo OK"

echo ""
echo "Deploy complete. Public URL: ${API_PUBLIC_URL:-https://\$CADDY_DOMAIN}"
echo "Android: set API_BASE_URL to ${API_PUBLIC_URL:-your HTTPS URL} and rebuild APK."
