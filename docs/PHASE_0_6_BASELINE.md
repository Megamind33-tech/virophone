# Phase 0.6 Baseline Audit

**Date:** 2026-09-14  
**Branch:** `cursor/phase06-docs-c131`  
**Prior baseline:** Pre–Phase 0.6 audit on `cursor/phase0-docs-7ff0`

## Executive summary

Phase 0.6 delivers Redis-backed realtime features, authenticated WebSocket signaling, TURN credential issuance, 128-bit ephemeral IDs, real Android NSD/Wi-Fi Direct discovery, and WebRTC voice engine selection. Integration tests pass (29/29). Hardware voice tests, Docker full-stack validation, and VPS deployment remain **BLOCKED**.

---

## Test baseline (post–Phase 0.6 implementation)

| Suite | Command | Result |
|-------|---------|--------|
| API unit | `cd apps/api && npm test` | **28/28 PASS** |
| API integration | `npm run test:integration` | **29/29 PASS** |
| — phase05 | `phase05.integration.spec.ts` | 21 PASS |
| — redis | `redis.integration.spec.ts` | 2 PASS |
| — phase06 | `phase06.integration.spec.ts` | 6 PASS |
| Android module unit | `./gradlew :feature:discovery:test :feature:calling:test ...` | **PASS** |
| Android app assemble | `./gradlew assembleDebug` | **FAIL** on build VM (`DiscoveryDiagnosticScreen.kt` unresolved ref) |
| Hardware voice/discovery | Physical devices | **BLOCKED** |
| Docker Compose | `docker compose up` | **BLOCKED** |

---

## Module inventory

### Backend (`apps/api`)

| Module | Phase 0.6 state |
|--------|-----------------|
| Auth, Devices, Users, Contacts, Directory, Connections, Blocks, Calls | Unchanged core — integration tests pass |
| **Redis** | **Implemented** — `RedisService`, health check |
| **Presence** | **Implemented** — `POST/GET /api/v1/presence`, block-aware |
| **Discovery (ephemeral)** | **Implemented** — register/resolve in Redis |
| **Signaling** | **Implemented** — WSS `/api/v1/signaling/ws` |
| **TURN** | **Implemented** — `POST /api/v1/turn/credentials` |
| **Production config** | **Implemented** — fail-closed `validateProductionConfig()` |

PostgreSQL migrations: `001_initial_schema.sql` (unchanged)

### Android (`apps/android`)

| Module | Phase 0.6 state |
|--------|-----------------|
| `:voice:webrtc` | **NEW** — `WebRtcVoiceEngine` + `stream-webrtc-android:1.1.3` |
| `:voice:linphone` | **STUB** — deferred from production path |
| `EphemeralIdGenerator` | **Upgraded** — 128-bit `vr1_*`, 15 min rotation |
| `NsdLanDiscovery` | **IMPLEMENTED** — real `_viroreach._tcp.` |
| `WifiDirectDiscovery` | **IMPLEMENTED** — real DNS-SD |
| `ServerAuthorizedPeerResolver` | **IMPLEMENTED** — server-backed resolve |
| `LocalNetworkDiscoveryService` | **IMPLEMENTED** — orchestrates NSD + Wi-Fi Direct |
| Diagnostic screen | Real discovery wiring + simulated peer option |

19 Gradle modules (added `:voice:webrtc`)

### Infrastructure

| Component | State |
|-----------|-------|
| `docker-compose.yml` | postgres, redis, api, caddy, coturn — **not runnable on build VM** |
| FlexiSIP | Config stub only — **removed from production path** |
| coturn | Config present — **not verified live** |

---

## Phase 0.6 deliverables vs blockers

| Deliverable | Status |
|-------------|--------|
| WebRTC via stream-webrtc-android (Apache 2.0) | **DONE** |
| Liblinphone/FlexiSIP deferred | **DONE** |
| 128-bit ephemeral IDs `vr1_*`, 15 min rotation | **DONE** |
| Real NSD + Wi-Fi Direct discovery | **DONE** (code); hardware **BLOCKED** |
| Redis presence + ephemeral + WS mapping | **DONE** |
| Authenticated WSS signaling | **DONE** |
| TURN HMAC-SHA1 credentials | **DONE** |
| Production fail-closed config | **DONE** |
| Offline trusted-contact discovery | **DESIGN ONLY** |
| Integration tests 29/29 | **PASS** |
| API unit tests 28/28 | **PASS** |
| Docker on build VM | **BLOCKED** |
| VPS deployment | **BLOCKED** (no credentials) |
| Hardware voice tests | **BLOCKED** |

---

## Verified claims

| Claim | Verified |
|-------|----------|
| PostgreSQL integration tests (21 phase05) | YES |
| Redis integration (health + ephemeral) | YES |
| Phase 0.6 security gates (6 tests) | YES |
| No client HMAC secret | YES |
| Server-side contact discovery | YES |
| libphonenumber normalization | YES |
| Blocking in discovery/calls/directory/presence | YES |
| Refresh token reuse detection | YES |
| Device revocation | YES |
| Production config fail-closed | YES (unit tests) |
| WebRTC dependency Apache 2.0 | YES (`build.gradle.kts`) |
| End-to-end voice on device | **NO** — not tested |
| Docker compose full stack | **NO** — blocked |

---

## Known failures / blockers

| Blocker | Detail |
|---------|--------|
| Docker | Daemon permission denied / prior overlay mount errors on cloud build VM |
| VPS | No production credentials in environment |
| Hardware | No physical Android devices in CI or build VM |
| App compile | `DiscoveryDiagnosticScreen.kt:43` — `discoveryServiceRef` forward reference (fix pending) |
| npm audit | 37 findings — disposition in [DEPENDENCY_SECURITY.md](DEPENDENCY_SECURITY.md) |
| Runtime permissions | NSD/Wi-Fi Direct need dangerous permissions — UX not implemented |

---

## Documentation index (Phase 0.6)

| Document | Purpose |
|----------|---------|
| [VOICE_ENGINE_DECISION.md](VOICE_ENGINE_DECISION.md) | WebRTC vs Liblinphone vs PJSIP ADR |
| [OFFLINE_TRUST_DISCOVERY.md](OFFLINE_TRUST_DISCOVERY.md) | Offline crypto design (not implemented) |
| [EPHEMERAL_ID_DESIGN.md](EPHEMERAL_ID_DESIGN.md) | `vr1_*` format and Redis registry |
| [DEPENDENCY_SECURITY.md](DEPENDENCY_SECURITY.md) | npm audit disposition |
| [DEPLOYMENT.md](DEPLOYMENT.md) | Production deployment (blocked) |
| [BACKUP_RESTORE.md](BACKUP_RESTORE.md) | Postgres backup procedures |
| [ACCEPTANCE_TESTS.md](ACCEPTANCE_TESTS.md) | PASS/FAIL/BLOCKED matrix |
| [DECISIONS.md](DECISIONS.md) | ADR-001 through ADR-018 |

---

## Related documents

- [ARCHITECTURE.md](ARCHITECTURE.md) — *Note: architecture doc may lag Phase 0.6 WebRTC/Redis updates*
- [ACCEPTANCE_TESTS.md](ACCEPTANCE_TESTS.md)
- [DECISIONS.md](DECISIONS.md)
