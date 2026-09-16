#!/usr/bin/env bash
# Cloud Agent start phase for Viro Reach.
# Per-boot reconciliation: starts the PostgreSQL and Redis daemons (systemd is
# not running in the VM) and applies any pending migrations. Idempotent and
# returns after services are ready.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

echo "=== Starting PostgreSQL ==="
if ! sudo -u postgres pg_isready -q 2>/dev/null; then
  sudo pg_ctlcluster 16 main start 2>/dev/null || sudo pg_ctlcluster 16 main restart
fi
for _ in $(seq 1 30); do sudo -u postgres pg_isready -q && break; sleep 1; done

echo "=== Starting Redis ==="
redis-cli ping >/dev/null 2>&1 || sudo redis-server /etc/redis/redis.conf --daemonize yes

echo "=== Applying pending migrations ==="
if [ -f "$ROOT/.env" ]; then
  ( cd apps/api && set -a && . "$ROOT/.env" && set +a && npm run migration:run ) || true
fi

echo "=== start complete ==="
