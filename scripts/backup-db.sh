#!/usr/bin/env bash
set -euo pipefail

# Viro Reach PostgreSQL backup script
# Usage: DATABASE_URL=postgresql://... ./scripts/backup-db.sh [output_dir]

OUTPUT_DIR="${1:-/var/backups/viro-reach}"
RETENTION_DAYS="${BACKUP_RETENTION_DAYS:-14}"
TIMESTAMP="$(date -u +%Y%m%dT%H%M%SZ)"
DATABASE_URL="${DATABASE_URL:?DATABASE_URL required}"

mkdir -p "$OUTPUT_DIR"
BACKUP_FILE="$OUTPUT_DIR/viro_reach_${TIMESTAMP}.sql.gz"

echo "Starting backup to $BACKUP_FILE"
pg_dump "$DATABASE_URL" | gzip > "$BACKUP_FILE"

if [[ ! -s "$BACKUP_FILE" ]]; then
  echo "ERROR: backup file empty" >&2
  exit 1
fi

echo "Backup complete: $(du -h "$BACKUP_FILE" | cut -f1)"

find "$OUTPUT_DIR" -name 'viro_reach_*.sql.gz' -mtime +"$RETENTION_DAYS" -delete 2>/dev/null || true
echo "Retention: ${RETENTION_DAYS} days"
