#!/usr/bin/env bash
# Cloud Agent install phase for Viro Reach.
# Idempotent, one-time setup that is baked into the environment snapshot:
# system packages (Postgres + Redis), Node dependencies/builds, local .env,
# database provisioning, and migrations.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

export DEBIAN_FRONTEND=noninteractive

echo "=== [1/6] System packages (PostgreSQL + Redis) ==="
if ! command -v pg_ctlcluster >/dev/null 2>&1 || ! command -v redis-server >/dev/null 2>&1; then
  sudo apt-get update -qq
  sudo apt-get install -y -qq postgresql postgresql-contrib redis-server
else
  echo "postgres and redis already installed"
fi

echo "=== [2/6] Local environment file ==="
if [ ! -f .env ]; then
  cp .env.example .env
  echo "created .env from .env.example"
fi
# Align the local Postgres credentials with what this script provisions below.
sed -i \
  -e 's|^POSTGRES_PASSWORD=.*|POSTGRES_PASSWORD=viro_dev_password|' \
  -e 's|^DATABASE_URL=.*|DATABASE_URL=postgresql://viro:viro_dev_password@localhost:5432/viro_reach|' \
  .env

echo "=== [3/6] Node dependencies and builds ==="
( cd packages/shared-types && npm install && npm run build )
( cd packages/api-contracts && npm install && npm run build )
( cd apps/api && npm install && npm run build )

echo "=== [4/6] Start PostgreSQL for provisioning ==="
sudo pg_ctlcluster 16 main start 2>/dev/null || sudo pg_ctlcluster 16 main restart
for _ in $(seq 1 30); do sudo -u postgres pg_isready -q && break; sleep 1; done

echo "=== [5/6] Provision database role, database, and extensions ==="
sudo -u postgres psql -v ON_ERROR_STOP=1 <<'SQL'
DO $$ BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'viro') THEN
    CREATE ROLE viro LOGIN PASSWORD 'viro_dev_password';
  END IF;
END $$;
SQL
sudo -u postgres psql -tc "SELECT 1 FROM pg_database WHERE datname='viro_reach'" | grep -q 1 \
  || sudo -u postgres createdb -O viro viro_reach
sudo -u postgres psql -d viro_reach -v ON_ERROR_STOP=1 \
  -c 'CREATE EXTENSION IF NOT EXISTS "pgcrypto"; CREATE EXTENSION IF NOT EXISTS "uuid-ossp"; ALTER SCHEMA public OWNER TO viro;'

# Bring up Redis so migrations and later boot steps have a running instance.
redis-cli ping >/dev/null 2>&1 || sudo redis-server /etc/redis/redis.conf --daemonize yes

echo "=== [6/6] Run database migrations ==="
( cd apps/api && set -a && . "$ROOT/.env" && set +a && npm run migration:run )

echo "=== install complete ==="
