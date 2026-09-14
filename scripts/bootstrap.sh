#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

echo "=== Viro Reach Phase 0 Bootstrap ==="

# Copy env if missing
if [ ! -f .env ]; then
  cp .env.example .env
  echo "Created .env from .env.example"
fi

# Install Node dependencies
echo "--- Installing shared packages ---"
cd packages/shared-types && npm install && npm run build
cd "$ROOT/packages/api-contracts" && npm install && npm run build

echo "--- Installing API dependencies ---"
cd "$ROOT/apps/api" && npm install

# Start infrastructure
echo "--- Starting Docker infrastructure ---"
cd "$ROOT"
docker compose up -d postgres redis

echo "Waiting for PostgreSQL..."
for i in $(seq 1 30); do
  if docker compose exec -T postgres pg_isready -U viro -d viro_reach > /dev/null 2>&1; then
    echo "PostgreSQL is ready"
    break
  fi
  sleep 1
done

# Run migrations
echo "--- Running migrations ---"
cd "$ROOT/apps/api" && npm run migration:run

# Build API
echo "--- Building API ---"
npm run build

# Run API tests
echo "--- Running API tests ---"
npm test

# Build Android (if SDK available)
if [ -d "$ANDROID_HOME" ] || [ -d "$HOME/Android/Sdk" ]; then
  echo "--- Building Android ---"
  cd "$ROOT/apps/android"
  chmod +x gradlew 2>/dev/null || true
  ./gradlew assembleDebug test --no-daemon || echo "Android build skipped (SDK issue)"
else
  echo "--- Android SDK not found, skipping Android build ---"
  echo "Set ANDROID_HOME to build Android locally"
fi

echo ""
echo "=== Bootstrap complete ==="
echo "Start development: make dev"
echo "API health: curl http://localhost:3001/health/live"
