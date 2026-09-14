# Backup and Restore (Phase 0.6)

**Scope:** Self-hosted PostgreSQL + Redis deployment per `docker-compose.yml`  
**Status:** Operational procedures documented; **not validated on blocked Docker VM**

## Data classification

| Store | Durability | Backup priority | Contains |
|-------|------------|-----------------|----------|
| **PostgreSQL** | Persistent (`postgres_data` volume) | **Critical** | Users, profiles, sessions, calls, blocks, security events |
| **Redis** | Ephemeral by design | **Low** | Presence (120s TTL), ephemeral IDs (900s TTL), WS connection map (3600s TTL) |
| **Caddy** | `caddy_data` volume | Medium | TLS certificates (auto-renewed) |
| **Android clients** | Local | N/A | Refresh tokens, Room cache, Keystore keys — device-scoped |

Redis data is **recoverable from client reconnection** — users re-register ephemeral IDs and presence on next app open. PostgreSQL is the system of record.

## PostgreSQL backup

### Logical backup (recommended)

```bash
# On host with Docker access
docker exec viro-postgres pg_dump -U viro -d viro_reach -Fc -f /tmp/viro_reach.dump

# Copy to host
docker cp viro-postgres:/tmp/viro_reach.dump ./backups/viro_reach_$(date +%Y%m%d_%H%M%S).dump
```

Without Docker:

```bash
pg_dump "$DATABASE_URL" -Fc -f viro_reach_$(date +%Y%m%d).dump
```

### Schedule

| Environment | Frequency | Retention |
|-------------|-----------|-----------|
| Production | Daily full + WAL archiving (recommended) | 30 days minimum |
| Staging | Weekly | 14 days |
| Development | Ad hoc | 7 days |

### What to protect

- `users`, `profiles`, `phone_identities`
- `sessions` (refresh token hashes — users must re-login after restore to old snapshot)
- `calls`, `blocks`, `connections`, `security_events`
- `schema_migrations` (must match `001_initial_schema.sql` + future migrations)

### What is NOT in Postgres

- Live presence state (Redis)
- Active ephemeral ID mappings (Redis)
- WebSocket connection routing (Redis)
- TURN credentials (generated on demand, short-lived)

## PostgreSQL restore

### Full restore (destructive)

```bash
# Stop API to prevent writes
docker compose stop api

# Drop and recreate database
docker exec -i viro-postgres psql -U viro -d postgres -c "DROP DATABASE IF EXISTS viro_reach;"
docker exec -i viro-postgres psql -U viro -d postgres -c "CREATE DATABASE viro_reach OWNER viro;"

# Restore
docker cp ./backups/viro_reach.dump viro-postgres:/tmp/restore.dump
docker exec viro-postgres pg_restore -U viro -d viro_reach -Fc /tmp/restore.dump

# Restart API
docker compose start api
```

### Point-in-time recovery

Requires WAL archiving configured at Postgres provisioning time — **not set up in Phase 0.6 compose file**. Add `archive_mode = on` and `archive_command` for production VPS.

### Post-restore verification

```bash
curl -s http://localhost:3001/health/ready | jq .
cd apps/api && npm run migration:run   # apply any migrations newer than backup
```

Run integration test subset against restored DB (staging only):

```bash
DATABASE_URL=... npm run test:integration
```

## Redis backup

### Policy: do not restore Redis for Phase 0.6

All Redis keys have TTL ≤ 3600 seconds. On Redis loss:

1. Restart Redis empty
2. Clients reconnect WebSocket → new `ws:device:{deviceId}` entries
3. Clients re-POST ephemeral IDs
4. Presence repopulates on connect

Optional RDB snapshot for debugging only:

```bash
docker exec viro-redis redis-cli BGSAVE
docker cp viro-redis:/data/dump.rdb ./backups/redis_debug.rdb
```

## Secret backup

| Secret | Backup method |
|--------|---------------|
| `JWT_*`, `CONTACT_HASH_SALT`, `TURN_SECRET`, `EPHEMERAL_SIGNING_SECRET` | Secret manager (Vault, cloud SM) — **never** in pg_dump |
| Database password | Co-locate with `DATABASE_URL` in secret manager |
| Android signing keystore | Offline secure storage (not in repo) |

Rotating `JWT_REFRESH_SECRET` invalidates all refresh tokens — plan maintenance window.

Rotating `CONTACT_HASH_SALT` invalidates all `phone_hash` values — requires re-hash migration script (not implemented).

## Disaster recovery targets (targets, not measured)

| Metric | Target | Phase 0.6 status |
|--------|--------|------------------|
| RPO (Postgres) | ≤ 24 hours (daily backup) | Procedure only |
| RTO (API) | ≤ 4 hours | Procedure only |
| Redis loss | 0 data recovery needed | Acceptable |

## Blockers

| Blocker | Impact |
|---------|--------|
| Docker BLOCKED on build VM | Cannot execute `docker exec` backup drills in agent environment |
| VPS deployment BLOCKED | No production backup automation deployed |
| No off-site backup tested | Restore procedure unverified |

## Related documents

- [DEPLOYMENT.md](DEPLOYMENT.md)
- [DATABASE_SCHEMA.md](DATABASE_SCHEMA.md)
- [PHASE_0_6_BASELINE.md](PHASE_0_6_BASELINE.md)
