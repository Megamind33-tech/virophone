#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${VIRO_ENV_FILE:-$ROOT/.env.vps}"
[[ -f "$ENV_FILE" ]] || { echo "Missing .env.vps" >&2; exit 1; }
set -a; source "$ENV_FILE"; set +a
SSH_OPTS=(-o BatchMode=yes)
[[ -n "${VIRO_SSH_KEY:-}" ]] && SSH_OPTS+=(-i "$VIRO_SSH_KEY")
ssh "${SSH_OPTS[@]}" "${VIRO_VPS_USER}@${VIRO_VPS_HOST}" \
  "cd '${VIRO_VPS_PATH}' && docker compose -f docker-compose.vps.yml --env-file .env.vps ps && curl -sf http://127.0.0.1:13001/health/ready && echo ready"
