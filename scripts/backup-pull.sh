#!/usr/bin/env bash
# Pulls Viro Reach backups OFF the VPS onto this machine.
#
# The nightly backup on the server protects against a bad migration, a dropped
# table or a deleted volume. It does not protect against losing the machine —
# backups sitting on the same disk as the database die with it. This is the
# off-site copy, and it is the one that matters for "the VPS is gone".
#
# Run it from your own PC. Uses the same SSH key as the deploy, so there are no
# new credentials to manage.
#
#   ./scripts/backup-pull.sh [local_dir]
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${VIRO_ENV_FILE:-$ROOT/.env.vps}"
LOCAL_DIR="${1:-${VIRO_LOCAL_BACKUP_DIR:-$HOME/viro-reach-backups}}"
KEEP_DAYS="${LOCAL_BACKUP_RETENTION_DAYS:-60}"

if [[ ! -f "$ENV_FILE" ]]; then
  echo "Missing $ENV_FILE" >&2
  exit 1
fi
set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

: "${VIRO_VPS_HOST:?Set VIRO_VPS_HOST in .env.vps}"
: "${VIRO_VPS_USER:?Set VIRO_VPS_USER in .env.vps}"

SSH_OPTS=(-o BatchMode=yes -o StrictHostKeyChecking=accept-new)
if [[ -n "${VIRO_SSH_KEY:-}" ]]; then
  SSH_OPTS+=(-i "$VIRO_SSH_KEY")
fi
REMOTE_DIR="${VIRO_VPS_PATH:-/opt/viro-reach}/backups"

mkdir -p "$LOCAL_DIR"
echo "==> Pulling from ${VIRO_VPS_USER}@${VIRO_VPS_HOST}:${REMOTE_DIR}"

# Kept deliberately simple: the archives are tiny (tens of KB a night), so
# mirroring the whole directory costs less than the bookkeeping needed to work
# out which files are new.
scp "${SSH_OPTS[@]}" -q \
  "${VIRO_VPS_USER}@${VIRO_VPS_HOST}:${REMOTE_DIR}/viro_reach_*.gz" \
  "$LOCAL_DIR/" 2>/dev/null || {
    echo "ERROR: nothing pulled — is the nightly backup running on the VPS?" >&2
    exit 1
  }

LATEST_DB="$(ls -1t "$LOCAL_DIR"/viro_reach_db_*.sql.gz 2>/dev/null | head -1 || true)"
if [[ -z "$LATEST_DB" ]]; then
  echo "ERROR: no database dump present locally after pull" >&2
  exit 1
fi

# Verify what landed here, not what the server said it wrote. A copy that
# cannot be read is not a backup, and a truncated transfer looks identical to a
# good one until the day it is needed.
if ! gzip -t "$LATEST_DB" 2>/dev/null; then
  echo "ERROR: $LATEST_DB is corrupt (failed gzip integrity check)" >&2
  exit 1
fi
TABLES="$(gzip -dc "$LATEST_DB" | grep -c 'CREATE TABLE' || true)"
if [[ "${TABLES:-0}" -lt 1 ]]; then
  echo "ERROR: $LATEST_DB contains no tables" >&2
  exit 1
fi

# Local copies are kept longer than the server's 14 days: this is the archive
# that survives the server, so depth matters more than disk here.
find "$LOCAL_DIR" -name 'viro_reach_*.gz' -mtime +"$KEEP_DAYS" -delete 2>/dev/null || true

echo "    verified: $(basename "$LATEST_DB") — ${TABLES} tables, $(du -h "$LATEST_DB" | cut -f1)"
echo "==> Local copies in $LOCAL_DIR ($(ls -1 "$LOCAL_DIR"/viro_reach_*.gz 2>/dev/null | wc -l) files, ${KEEP_DAYS}-day retention)"
