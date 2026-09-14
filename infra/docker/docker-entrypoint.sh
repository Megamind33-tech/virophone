#!/bin/sh
set -e
echo "Running database migrations..."
node dist/database/run-migrations.js
echo "Starting Viro Reach API..."
exec node dist/main.js
