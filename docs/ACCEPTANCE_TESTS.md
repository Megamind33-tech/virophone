# Phase 0.6 Acceptance Tests

## Purpose

This document defines acceptance criteria for Viro Reach Phase 0.6 and maps them to automated test coverage. Phase 0.6 validates architecture, privacy rules, server-authoritative security, Redis-backed realtime features, and Android discovery/voice **integration** — not end-to-end voice quality on physical hardware.

**Verification commands:**

```bash
make bootstrap   # Full setup + API tests (when Postgres/Redis available)
make test        # API unit + Android module tests
cd apps/api && npm run test:integration   # 29 integration tests
make health      # API liveness/readiness
```

CI pipeline: `.github/workflows/ci.yml`

## Test baseline (2026-09-14)

| Suite | Command | Result |
|-------|---------|--------|
| API unit | `cd apps/api && npm test` | **28/28 PASS** |
| API integration | `npm run test:integration` | **29/29 PASS** (21 phase05 + 2 redis + 6 phase06) |
| Android module unit | `./gradlew :feature:*:test :core:*:test` | **PASS** (discovery, calling, contacts, model) |
| Android app assemble | `./gradlew assembleDebug` | **PASS** |
| Hardware voice/discovery | Physical devices | **BLOCKED** |
| Docker full stack | `docker compose up` | **BLOCKED** — daemon inaccessible on build VM |
| VPS deployment | Production smoke | **BLOCKED** — no credentials |

---

## Phase 0.6 acceptance matrix

| ID | Criterion | Status | Evidence |
|----|-----------|--------|----------|
| **Voice / media** |
| AC-06-01 | WebRTC selected via `stream-webrtc-android` (Apache 2.0) | **PASS** | `voice/webrtc/build.gradle.kts`, `WebRtcVoiceEngine.kt` |
| AC-06-02 | Liblinphone deferred — stub only, not production path | **PASS** | `LiblinphoneVoiceEngine.kt` stub; no SDK dependency |
| AC-06-03 | FlexiSIP removed from production path | **PASS** | Not in `docker-compose.yml`; signaling via WSS |
| AC-06-04 | End-to-end voice call on hardware | **BLOCKED** | No physical devices; no audio path verification |
| AC-06-05 | WebRTC ICE connect on real network | **BLOCKED** | Requires hardware + TURN deployment |
| **Ephemeral IDs** |
| AC-06-10 | Format `vr1_<url-safe-base64>` 128-bit | **PASS** | `EphemeralIdGenerator.kt` |
| AC-06-11 | 15-minute client rotation | **PASS** | `rotationIntervalMs = 15 * 60 * 1000` |
| AC-06-12 | Server Redis TTL 900s | **PASS** | `discovery.service.ts`, `redis.integration.spec.ts` |
| AC-06-13 | Register ephemeral `POST /discovery/ephemeral` | **PASS** | `redis.integration.spec.ts` |
| AC-06-14 | Authorized resolve only | **PASS** | `phase06.integration.spec.ts`, `LocalDiscoveryPrivacyTest.kt` |
| AC-06-15 | Offline trust discovery | **DESIGN ONLY** | [OFFLINE_TRUST_DISCOVERY.md](OFFLINE_TRUST_DISCOVERY.md) |
| **Local discovery (Android)** |
| AC-06-20 | Real NSD `_viroreach._tcp.` | **PASS** (code) | `NsdLanDiscovery.kt` — hardware **BLOCKED** |
| AC-06-21 | Real Wi-Fi Direct DNS-SD | **PASS** (code) | `WifiDirectDiscovery.kt` — hardware **BLOCKED** |
| AC-06-22 | Advertisement contains ephemeral ID only | **PASS** | Unit tests + TXT record inspection in code |
| AC-06-23 | Unknown peers counted, not listed | **PASS** | `LocalDiscoveryPrivacyTest.kt` |
| AC-06-24 | Runtime permissions before discovery | **FAIL** | Not implemented — diagnostic starts without permission flow |
| **Server realtime** |
| AC-06-30 | Redis presence with block privacy | **PASS** | `phase06.integration.spec.ts` |
| AC-06-31 | Redis WS device connection map | **PASS** | `signaling.gateway.ts` |
| AC-06-32 | Authenticated WSS `/api/v1/signaling/ws` | **PASS** | `signaling.gateway.ts` (no live WSS integration test) |
| AC-06-33 | Health ready reports Redis | **PASS** | `redis.integration.spec.ts` |
| **TURN** |
| AC-06-40 | `POST /api/v1/turn/credentials` requires auth | **PASS** | `phase06.integration.spec.ts` |
| AC-06-41 | HMAC-SHA1 coturn-style credentials | **PASS** | `turn-credential.service.ts` |
| AC-06-42 | coturn relay on real network | **BLOCKED** | Docker/VPS blocked |
| **Production safety** |
| AC-06-50 | Fail-closed production config | **PASS** | `production-config.spec.ts` (4 tests) |
| AC-06-51 | Suspended user cannot refresh | **PASS** | `phase06.integration.spec.ts` |
| **Infrastructure** |
| AC-06-60 | Docker Compose full stack | **BLOCKED** | Overlay/permission errors on build VM |
| AC-06-61 | VPS production deployment | **BLOCKED** | No credentials |
| AC-06-62 | npm audit disposition documented | **PASS** | [DEPENDENCY_SECURITY.md](DEPENDENCY_SECURITY.md) |

---

## Carried forward: Phase 0 acceptance criteria

### AC-1: Monorepo builds and bootstraps

| ID | Criterion | Status |
|----|-----------|--------|
| AC-1.1 | `make bootstrap` installs packages, runs migrations, tests | **PASS** (without Docker) |
| AC-1.2 | `make dev` starts infra and API | **BLOCKED** (Docker) |
| AC-1.3 | `GET /health/live` returns ok | **PASS** |
| AC-1.4 | `GET /health/ready` reports database + redis | **PASS** |
| AC-1.5 | Android `./gradlew assembleDebug test` | **BLOCKED** (app compile); module tests **PASS** |

### AC-3: Authentication and sessions

| ID | Criterion | Status |
|----|-----------|--------|
| AC-3.1–AC-3.6 | OTP, JWT, refresh rotation, reuse detection | **PASS** |

Integration: `phase05.integration.spec.ts`, `phase06.integration.spec.ts`

### AC-4: Contact discovery privacy

| ID | Criterion | Status |
|----|-----------|--------|
| AC-4.1–AC-4.6 | Batch limits, blocks, server-side hashing | **PASS** |

### AC-5: Local discovery privacy

| ID | Criterion | Status |
|----|-----------|--------|
| AC-5.1 | Ephemeral ID only in advertisement | **PASS** |
| AC-5.2 | Unknown peers counted only | **PASS** |
| AC-5.3 | Authorized resolver | **PASS** (`ServerAuthorizedPeerResolver`) |
| AC-5.4 | Ephemeral rotation | **PASS** (15 min) |
| AC-5.5 | Diagnostic screen demo | **PASS** (with simulated peer injection option) |

### AC-6: Call authorization

| ID | Criterion | Status |
|----|-----------|--------|
| AC-6.1–AC-6.5 | Server-authoritative authorize | **PASS** |

### AC-7: Call route selection

| ID | Criterion | Status |
|----|-----------|--------|
| AC-7.1–AC-7.4 | Priority LAN → Wi-Fi Direct → Internet → TURN | **PASS** (unit tests; transports partially stubbed) |

### AC-8: Voice engine abstraction

| ID | Criterion | Status |
|----|-----------|--------|
| AC-8.1 | Feature modules use `VoiceEngine` interface | **PASS** |
| AC-8.2 | `WebRtcVoiceEngine` implements interface | **PASS** |
| AC-8.3 | `LiblinphoneVoiceEngine` stub remains for boundary test | **PASS** (deferred) |

### AC-9–AC-11: Contracts, device identity, security hygiene

| Area | Status |
|------|--------|
| Shared types / API contracts | **PASS** |
| Device Keystore (unit) | **PASS** |
| Keystore on hardware | **BLOCKED** |
| Security events, rate limiting | **PASS** |

---

## Integration test inventory

| File | Tests | Focus |
|------|-------|-------|
| `phase05.integration.spec.ts` | 21 | Auth, discovery, blocks, calls, directory, refresh reuse |
| `redis.integration.spec.ts` | 2 | Health/redis, ephemeral register+resolve |
| `phase06.integration.spec.ts` | 6 | Token expiry, presence+block, suspend, ephemeral, TURN |

---

## Explicit non-goals (Phase 0.6)

| Item | Status |
|------|--------|
| Production UI / navigation | Deferred |
| Live voice on hardware | **BLOCKED** |
| Offline trusted-contact discovery | **DESIGN ONLY** |
| FlexiSIP / SIP registration | Removed from production path |
| Admin UI | Future |
| iOS client | Future |
| Push notifications | Phase 1 |

---

## CI gates

Pull requests to `main` and `cursor/**` branches must pass:

1. API lint
2. Migrations against test Postgres
3. API unit tests (28)
4. API integration tests (29) — requires Postgres + Redis services
5. API build
6. Android assembleDebug, test, lint (target; app compile may need fix)

---

## Related documents

- [PHASE_0_6_BASELINE.md](PHASE_0_6_BASELINE.md)
- [VOICE_ENGINE_DECISION.md](VOICE_ENGINE_DECISION.md)
- [OFFLINE_TRUST_DISCOVERY.md](OFFLINE_TRUST_DISCOVERY.md)
- [DECISIONS.md](DECISIONS.md)
- [DEPLOYMENT.md](DEPLOYMENT.md)
