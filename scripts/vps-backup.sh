#!/usr/bin/env bash
# Nightly backup of the Viro Reach database AND uploaded avatars.
#
# Runs ON THE VPS, from cron. Uses `docker exec` rather than scripts/backup-db.sh
# because Postgres listens only on the compose-internal network — there is no
# published port for the host to reach, and pg_dump is not installed on the host.
#
# Avatars are included because they live in a Docker volume rather than object
# storage: a database-only backup would restore every profile with a broken
# image link.
set -euo pipefail

ROOT="${VIRO_ROOT:-/opt/viro-reach}"
OUTPUT_DIR="${VIRO_BACKUP_DIR:-/opt/viro-reach/backups}"
RETENTION_DAYS="${BACKUP_RETENTION_DAYS:-14}"
TIMESTAMP="$(date -u +%Y%m%dT%H%M%SZ)"
DB_CONTAINER="viro-reach-postgres"
API_CONTAINER="viro-reach-api"

mkdir -p "$OUTPUT_DIR"

if ! docker ps --format '{{.Names}}' | grep -qx "$DB_CONTAINER"; then
  echo "ERROR: $DB_CONTAINER is not running — nothing backed up." >&2
  exit 1
fi

# Credentials come from the container's own environment, so this never needs a
# second copy of the password that could drift from the real one.
POSTGRES_USER="$(docker exec "$DB_CONTAINER" printenv POSTGRES_USER)"
POSTGRES_DB="$(docker exec "$DB_CONTAINER" printenv POSTGRES_DB)"

DB_FILE="$OUTPUT_DIR/viro_reach_db_${TIMESTAMP}.sql.gz"
echo "==> Dumping $POSTGRES_DB"
docker exec "$DB_CONTAINER" pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" | gzip > "$DB_FILE"

# A dump that "succeeded" but contains no schema is the classic silent backup
# failure: the file exists, cron reports success, and it restores nothing.
if [[ ! -s "$DB_FILE" ]]; then
  echo "ERROR: dump is empty" >&2
  rm -f "$DB_FILE"
  exit 1
fi
# grep -c rather than grep -q: -q exits on the first match, which SIGPIPEs the
# gzip feeding it, and under `set -o pipefail` that reads as a failed check —
# condemning a perfectly good backup.
TABLE_COUNT="$(gzip -dc "$DB_FILE" | grep -c 'CREATE TABLE' || true)"
if [[ "${TABLE_COUNT:-0}" -lt 1 ]]; then
  echo "ERROR: dump contains no CREATE TABLE — refusing to keep it" >&2
  rm -f "$DB_FILE"
  exit 1
fi
echo "    tables: $TABLE_COUNT"
echo "    db: $(du -h "$DB_FILE" | cut -f1)"

AVATAR_FILE="$OUTPUT_DIR/viro_reach_avatars_${TIMESTAMP}.tar.gz"
echo "==> Archiving avatars"
if docker ps --format '{{.Names}}' | grep -qx "$API_CONTAINER"; then
  docker exec "$API_CONTAINER" tar -czf - -C /app/uploads avatars > "$AVATAR_FILE" 2>/dev/null || {
    echo "WARN: avatar archive failed (continuing — database backup is kept)" >&2
    rm -f "$AVATAR_FILE"
  }
  [[ -f "$AVATAR_FILE" ]] && echo "    avatars: $(du -h "$AVATAR_FILE" | cut -f1)"
else
  echo "WARN: $API_CONTAINER not running — avatars skipped" >&2
fi

find "$OUTPUT_DIR" -name 'viro_reach_*.gz' -mtime +"$RETENTION_DAYS" -delete 2>/dev/null || true
echo "==> Done. Retention ${RETENTION_DAYS} days. Current backups:"
ls -1t "$OUTPUT_DIR" | head -6
