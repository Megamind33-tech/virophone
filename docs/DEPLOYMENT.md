# Deployment Guide (Phase 0.6)

**Status:** Documented for production target; **VPS deployment BLOCKED** (no credentials in build environment)

## Overview

Viro Reach Phase 0.6 production stack:

| Component | Role |
|-----------|------|
| **API** (NestJS) | REST + WSS signaling, TURN credential issuance |
| **PostgreSQL 16** | System of record (users, sessions, calls, blocks) |
| **Redis 7** | Presence, ephemeral ID mapping, WebSocket device connection map |
| **Caddy** | TLS termination, reverse proxy |
| **coturn** | STUN/TURN relay (HMAC-SHA1 time-limited credentials) |

**Not in production path:** FlexiSIP (deferred), Liblinphone SDK (deferred).

## Environment requirements

### Required secrets (production fail-closed)

`validateProductionConfig()` in `apps/api/src/config/production-config.ts` refuses startup when `NODE_ENV=production` and any of the following are missing or contain dev fallback patterns (`dev_*`, `change_me`, etc.):

| Variable | Purpose |
|----------|---------|
| `DATABASE_URL` | PostgreSQL connection |
| `REDIS_URL` | Redis connection |
| `JWT_ACCESS_SECRET` | Access token signing (min 32 chars) |
| `JWT_REFRESH_SECRET` | Refresh token HMAC |
| `CONTACT_HASH_SALT` | Server-side phone hash |
| `TURN_SECRET` | coturn `use-auth-secret` HMAC key |
| `EPHEMERAL_SIGNING_SECRET` | Reserved for signed ephemeral bindings |

### Optional / operational

| Variable | Default | Purpose |
|----------|---------|---------|
| `API_PORT` | `3001` | HTTP listen port |
| `TURN_HOST` | `localhost` | TURN server hostname in issued URLs |
| `TURN_PORT` | `3478` | TURN/STUN port |
| `TURN_REALM` | `viro-reach.local` | coturn realm |
| `TURN_CREDENTIAL_TTL_SECONDS` | `3600` | TURN username expiry |
| `EPHEMERAL_TTL_SECONDS` | `900` | Redis ephemeral key TTL |
| `OTP_PROVIDER` | `console` | **Must not be `test` in production** |

## Docker Compose (reference)

File: `docker-compose.yml`

Services: `postgres`, `redis`, `api`, `caddy`, `coturn`

```bash
# Development (requires working Docker daemon)
docker compose up -d postgres redis
cd apps/api && npm run migration:run && npm run start:dev
```

### Docker BLOCKED on build VM

Docker Compose is **not operational** on the Phase 0.6 cloud build VM:

| Symptom | Cause |
|---------|-------|
| `permission denied` connecting to `/var/run/docker.sock` | No Docker daemon access for agent |
| Prior overlay mount errors | Overlay filesystem incompatible with nested container storage |

**Impact:**

- Cannot validate full `docker compose up --build` in CI agent environment
- Integration tests run against **host-installed** PostgreSQL and Redis (or GitHub Actions services in `.github/workflows/ci.yml`)
- Production image build (`infra/docker/Dockerfile.api`) is defined but not verified on blocked VM

**Workaround for local/CI:** GitHub Actions `backend` job uses service containers for Postgres and Redis; API runs directly with `npm test` / `npm run test:integration`.

## API deployment (non-Docker)

```bash
cd packages/shared-types && npm ci && npm run build
cd packages/api-contracts && npm ci && npm run build
cd apps/api && npm ci && npm run build
NODE_ENV=production node dist/main.js
```

Startup calls `validateProductionConfig()` before binding port.

### Health checks

| Endpoint | Expected |
|----------|----------|
| `GET /health/live` | `{ status: "ok" }` |
| `GET /health/ready` | `{ status: "ready", database: "connected", redis: "connected" }` |

## TLS and routing (Caddy)

Config stub: `infra/proxy/Caddyfile`

Production must expose:

- HTTPS → API REST (`/api/v1/*`)
- WSS → `/api/v1/signaling/ws?token=<JWT>`

WebSocket connections authenticate via JWT query parameter (`signaling.gateway.ts`).

## coturn

Config: `infra/coturn/turnserver.conf`

```
use-auth-secret
static-auth-secret=<TURN_SECRET>   # must match API env
realm=viro-reach.local
```

API issues credentials (`turn-credential.service.ts`):

```
username = "{expiry}:{userId}:{deviceId}"
credential = base64(HMAC-SHA1(secret, username))
```

Client obtains credentials: `POST /api/v1/turn/credentials` (JWT required, rate-limited 10/min).

## Android client

- `buildConfigField API_BASE_URL` in `apps/android/app/build.gradle.kts` — override per environment
- Release build: `./gradlew assembleRelease` (requires signing config not in repo)
- WebRTC module: `:voice:webrtc` with `stream-webrtc-android`

**Hardware deployment testing:** BLOCKED — no physical devices in build VM.

## VPS deployment checklist (BLOCKED)

The following steps are documented but **not executed** in Phase 0.6 due to missing VPS credentials:

- [ ] Provision VPS (Ubuntu 24.04+ recommended)
- [ ] Install Docker or native Node 20 + Postgres 16 + Redis 7
- [ ] Configure DNS and TLS certificates (Caddy automatic HTTPS)
- [ ] Set production secrets via secret manager (not `.env` in repo)
- [ ] Run migrations: `npm run migration:run`
- [ ] Deploy coturn with `network_mode: host` or equivalent UDP exposure
- [ ] Configure firewall: 443/tcp, 3478/udp+tcp, TURN relay port range
- [ ] Smoke test: health, OTP, signaling WSS, TURN credentials
- [ ] **Do not claim voice call verification until hardware test completes**

## CI reference

`.github/workflows/ci.yml`:

- Backend: lint, migrate, unit tests (28), integration tests (29), build
- Android: `./gradlew assembleDebug test lint`

## Related documents

- [BACKUP_RESTORE.md](BACKUP_RESTORE.md)
- [DEPENDENCY_SECURITY.md](DEPENDENCY_SECURITY.md)
- [PHASE_0_6_BASELINE.md](PHASE_0_6_BASELINE.md)
