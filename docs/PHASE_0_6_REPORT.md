# Phase 0.6 Final Report — Viro Reach

**Date:** 2026-09-14  
**Commit:** `54e5b16`  
**Branch:** `cursor/phase06-docs-c131`

## EXECUTIVE VERDICT

**PHASE 1: NO-GO**

Phase 0.6 established a real software foundation — WebRTC engine selection, authenticated signaling, Redis-backed presence/ephemeral state, 128-bit ephemeral IDs, real NSD/Wi-Fi Direct code, production fail-closed config, and expanded integration tests (29/29 pass). However, the defining product gates cannot be verified without hardware and deployed infrastructure:

- No two-device voice call (Internet P2P, TURN, LAN)
- Same-Wi-Fi **without Internet** is **DESIGN ONLY** (offline trust not implemented)
- Docker Compose and VPS deployment **BLOCKED** on this environment
- Physical Keystore verification **BLOCKED**

Proceed to a **tightly scoped Phase 1A hardware verification sprint** before full product UI development.

---

## BASELINE

At commit `6cb33bf` (pre–Phase 0.6):

| Suite | Result |
|-------|--------|
| API unit | 24/24 PASS |
| API integration | 21/21 PASS |
| Android | `assembleDebug test` PASS |

Stubs: Liblinphone voice, simulated LAN discovery, no Redis/presence/signaling/TURN, 32-bit ephemeral IDs.

---

## CHANGES MADE

### Backend
- **Production fail-closed** — `validateProductionConfig()` refuses startup in production without secrets (`production-config.ts` + 4 unit tests)
- **Redis module** — health ping, JSON key helpers
- **Discovery** — `POST /discovery/ephemeral`, `POST /discovery/ephemeral/resolve` with block checks
- **Presence** — `POST/GET /presence` with blocking → OFFLINE
- **Signaling** — authenticated WSS at `/api/v1/signaling/ws` (JWT + device revocation)
- **TURN** — `POST /turn/credentials` (HMAC-SHA1 coturn-style, rate-limited)
- **Suspended user** — refresh blocked when `status != ACTIVE`

### Android
- **128-bit ephemeral IDs** — `vr1_<url-safe-base64>` with 15-minute rotation
- **Real NSD** — `NsdLanDiscovery` (`_viroreach._tcp.`, TXT: protocol/version/eid/cap only)
- **Wi-Fi Direct** — `WifiDirectDiscovery` (DNS-SD, same ephemeral principles)
- **ServerAuthorizedPeerResolver** — online ephemeral → authorized contact mapping
- **WebRtcVoiceEngine** — `:voice:webrtc` using `stream-webrtc-android` (Apache 2.0)
- **Diagnostic screen** — REAL/SIMULATED/STUBBED labels, no fabricated production state

### Infrastructure / scripts
- `scripts/backup-db.sh` — `pg_dump` with retention
- `.env.example` — `EPHEMERAL_SIGNING_SECRET`, TURN TTL vars

### Documentation
- ADR-011–018, `VOICE_ENGINE_DECISION.md`, `OFFLINE_TRUST_DISCOVERY.md` (DESIGN ONLY), `DEPENDENCY_SECURITY.md`, updated acceptance matrix

---

## VOICE ENGINE DECISION

**Chosen: Native WebRTC** (`WebRtcVoiceEngine` behind `VoiceEngine`)

| Criterion | WebRTC | Liblinphone | PJSIP |
|-----------|--------|-------------|-------|
| Commercial license | Apache 2.0 (Stream build) | **AGPL** — copyleft risk | GPL/commercial dual |
| Android maturity | High | High | Medium |
| ICE/STUN/TURN | Native | Yes | Yes |
| APK size | Moderate | Large | Moderate |
| SIP coupling | None needed | Required | Required |

**Rejected:** Liblinphone (AGPL), FlexiSIP (deferred/removed from compose path). Linphone stub retained for boundary reference only.

Full ADR: [VOICE_ENGINE_DECISION.md](VOICE_ENGINE_DECISION.md)

---

## SIGNALING

```
PHONE A ──HTTPS/WSS──► VIRO API (/api/v1/signaling/ws) ──WSS──► PHONE B
MEDIA:   PHONE A ◄──────── WebRTC P2P / TURN ────────► PHONE B
```

- JWT in query param on connect; device revocation checked
- Redis maps `ws:device:{deviceId}` for relay
- `signal` messages bound to `callId` + device IDs
- Call authorization still required via `POST /calls/authorize` before media

---

## VPS DEPLOYMENT

**BLOCKED** — no VPS credentials in environment.

## DOCKER COMPOSE

**BLOCKED** — overlay mount error on build VM:

```
failed to solve: mount source: "overlay" ... err: invalid argument
```

`docker compose config` succeeds; `docker compose up` / `build` fail.

---

## DATABASE

| Item | Status |
|------|--------|
| PostgreSQL integration | **PASS** — 21/21 phase05 tests |
| Migrations clean boot | **PASS** |
| Backup script | **PASS** — `scripts/backup-db.sh` created |
| Restore test | **NOT IMPLEMENTED** — no production-like restore executed on this VM |

---

## REDIS

| Use | Implementation |
|-----|----------------|
| Ephemeral ID mapping | `ephemeral:{eid}` TTL 900s |
| Presence | `presence:{userId}` TTL 120s |
| WS device map | `ws:device:{deviceId}` TTL 3600s |

**Integration tests:** 2/2 PASS (`redis.integration.spec.ts`)

---

## COTURN

| Item | Status |
|------|--------|
| Config in compose | Present |
| Credential API | **PASS** — authenticated HMAC-SHA1 issuance |
| Real allocation | **BLOCKED** — Docker unavailable |
| TURN relay proof | **BLOCKED** — no `iceTransportPolicy=relay` hardware test |

---

## LOCAL DISCOVERY

| Item | Status | Evidence |
|------|--------|----------|
| Real NSD code | **PASS** | `NsdLanDiscovery.kt` |
| Real Wi-Fi Direct code | **PASS** | `WifiDirectDiscovery.kt` |
| 128-bit ephemeral IDs | **PASS** | `EphemeralIdGenerator.kt` |
| Anonymous peer behavior | **PASS** | `LocalDiscoveryPrivacyTest.kt` |
| Hardware NSD/WFD test | **BLOCKED** | No physical devices |
| Unknown peer privacy (3 devices) | **BLOCKED** | Hardware required |

---

## OFFLINE TRUST

**Status: DESIGN ONLY**

Architecture documented in [OFFLINE_TRUST_DISCOVERY.md](OFFLINE_TRUST_DISCOVERY.md) using HMAC-SHA256 trust tokens, bounded lifetimes, and eventual block sync. **No production implementation.** Same-Wi-Fi-without-Internet calling cannot pass until this is built and verified.

---

## DEVICE IDENTITY

| Test | Status |
|------|--------|
| Keystore EC key persistence | **BLOCKED** — hardware required |
| Revocation enforcement | **PASS** (server) — phase05 integration |

---

## REAL VOICE TESTS

| Scenario | Status |
|----------|--------|
| Internet P2P | **BLOCKED** |
| TURN relay (forced relay) | **BLOCKED** |
| Same Wi-Fi with Internet | **BLOCKED** |
| Same Wi-Fi without Internet | **FAIL / DESIGN ONLY** |
| Wi-Fi Direct without Internet | **BLOCKED** |

---

## PRIVACY TEST

| Item | Status |
|------|--------|
| Unit: unknown peers not exposed | **PASS** — 3 anonymous, 0 authorized |
| Unit: authorized contact resolved | **PASS** |
| 3-device LAN hardware test | **BLOCKED** |

---

## SECURITY

| Item | Status |
|------|--------|
| Production fail-closed | **PASS** |
| Suspended user | **PASS** |
| Expired/invalid token → 401 + refresh | **PASS** |
| Presence block hides as OFFLINE | **PASS** |
| npm audit (37 findings) | **PASS** — dispositioned in `DEPENDENCY_SECURITY.md` |
| Secret scan | **PASS** — no production secrets committed |
| Rate limits | **PARTIAL** — TURN throttled; full abuse integration tests not added |

---

## TEST RESULTS

```bash
cd apps/api && npm test                    # 28/28 PASS
cd apps/api && npm run test:integration    # 29/29 PASS
cd apps/android && ./gradlew assembleDebug test  # BUILD SUCCESSFUL
```

Breakdown: 21 phase05 + 2 redis + 6 phase06 integration tests.

---

## BUILD RESULTS

| Component | Result |
|-----------|--------|
| API `npm run build` | **PASS** |
| Android `assembleDebug` | **PASS** |
| Docker Compose | **BLOCKED** |

---

## PERFORMANCE BASELINE

Not measured on hardware. Server-only paths (auth, ephemeral resolve, TURN credential issuance) complete within integration test timeouts (<5s total suite).

---

## KNOWN LIMITATIONS

1. Offline trusted-contact discovery is design-only
2. WebRTC signaling not wired end-to-end in Android app UI
3. `InternetSipCallTransport` name retained; WebRTC transport rename deferred
4. No FCM/push for background incoming calls
5. Runtime permission flow before discovery not implemented
6. Call session replay protection partially implemented (authorization TTL exists; full signaling bind tests incomplete)
7. Liblinphone stub still present (documented as deferred)

---

## REMAINING BLOCKERS

1. **Hardware verification sprint** — Keystore, two-device voice, LAN/TURN routes
2. **Offline trust implementation** — defining Viro capability
3. **Docker/VPS deployment** — coturn + HTTPS/WSS in production
4. **TURN relay proof** — forced relay ICE test
5. **DB restore verification** — backup script untested end-to-end

---

## PHASE 0.6 ACCEPTANCE MATRIX (summary)

| Requirement | Status | Evidence |
|-------------|--------|----------|
| Backend regression tests | **PASS** | 28 unit + 29 integration |
| Production fail-closed | **PASS** | `production-config.spec.ts` |
| Redis integration | **PASS** | `redis.integration.spec.ts` |
| Voice engine selected (WebRTC) | **PASS** | ADR-011, `:voice:webrtc` |
| Real signaling (WSS) | **PASS** | `signaling.gateway.ts` |
| Real NSD implemented | **PASS** (code) | `NsdLanDiscovery.kt` |
| 128-bit ephemeral IDs | **PASS** | `EphemeralIdGenerator.kt` |
| Authorized peer resolver | **PASS** | `ServerAuthorizedPeerResolver.kt` |
| Unknown peers anonymous | **PASS** | `LocalDiscoveryPrivacyTest.kt` |
| coturn operational | **BLOCKED** | Docker overlay error |
| TURN relay proven | **BLOCKED** | No hardware/deployment |
| Two-device Internet voice | **BLOCKED** | No hardware |
| Same-Wi-Fi no Internet | **DESIGN ONLY** | `OFFLINE_TRUST_DISCOVERY.md` |
| Wi-Fi Direct voice | **BLOCKED** | No hardware |
| VPS deployment | **BLOCKED** | No credentials |
| Physical Keystore test | **BLOCKED** | No hardware |
| Android builds | **PASS** | `assembleDebug` |
| npm audit dispositioned | **PASS** | `DEPENDENCY_SECURITY.md` |

---

## PHASE 1 GO / NO-GO

**NO-GO** for full Phase 1 product development.

**Rationale:** The software architecture is sound and testable, but Viro Reach's core value proposition — authorized local calling when the Internet disappears — remains **DESIGN ONLY**. Voice has not been proven on any physical device. Production infrastructure cannot be validated in this environment.

**Recommended next step:** Phase 1A — hardware + VPS verification gate (deploy stack on foreign VPS, run two-device matrix, implement offline trust POC, prove TURN relay) before accelerating UI/product work.

---

## Documentation index

- [PHASE_0_6_BASELINE.md](PHASE_0_6_BASELINE.md)
- [ACCEPTANCE_TESTS.md](ACCEPTANCE_TESTS.md)
- [VOICE_ENGINE_DECISION.md](VOICE_ENGINE_DECISION.md)
- [OFFLINE_TRUST_DISCOVERY.md](OFFLINE_TRUST_DISCOVERY.md)
- [DECISIONS.md](DECISIONS.md) (ADR-011–018)
