# Phase 1A Report — Viro Reach

**Date:** 2026-09-14  
**Branch:** `cursor/phase06-docs-c131`  
**Baseline:** `docs/PHASE_1A_BASELINE.md`

## EXECUTIVE VERDICT

**PHASE 1A: INCOMPLETE — hardware and VPS verification remain BLOCKED**

Phase 1A software implementation advanced significantly: call session security in Redis, structured signaling, offline trust API + Android POC, WebRTC `CallManager`, engineering auth/call screens, production `docker-compose.prod.yml`, and `btag` offline discovery binding. **No two-device voice call was proven** because physical Android hardware and foreign VPS access are unavailable in this environment.

---

## TEST RESULTS (automated)

| Suite | Result |
|-------|--------|
| API unit | **28/28 PASS** |
| API integration | **30/30 PASS** (added offline-trust) |
| Android `assembleDebug test` | **PASS** |

---

## INFRASTRUCTURE

| Requirement | Status | Evidence |
|-------------|--------|----------|
| Foreign VPS deployment | **BLOCKED** | No SSH credentials in environment |
| Docker Compose (dev VM) | **BLOCKED** | Overlay mount error persists |
| `docker-compose.prod.yml` | **PASS** (config) | Private PG/Redis, Caddy TLS template |
| HTTPS live endpoint | **BLOCKED** | No deployed domain |
| coturn operational | **BLOCKED** | Docker unavailable |
| PostgreSQL private | **PASS** (prod compose design) | No public ports in prod file |
| Redis private | **PASS** (prod compose design) | Internal network only |

---

## SIGNALING & CALLS (software)

| Requirement | Status | Evidence |
|-------------|--------|----------|
| Call session in Redis | **PASS** | `call-session.service.ts` |
| Structured signaling events | **PASS** | `signaling.gateway.ts` — invite/offer/answer/ice/end |
| Participant validation | **PASS** | `isParticipant()` checks on every event |
| WSS auth (JWT + device) | **PASS** | Connection handler unchanged + tested in Phase 0.6 |
| Android SignalingClient | **PASS** (code) | `SignalingClient.kt` |
| Android CallManager | **PASS** (code) | `CallManager.kt` — authorize → WSS → WebRTC |
| Engineering call UI | **PASS** (code) | `EngineeringNav.kt` |

---

## OFFLINE TRUST POC

| Requirement | Status | Evidence |
|-------------|--------|----------|
| Server trust material API | **PASS** | `GET /offline-trust/material` |
| Offline call tickets API | **PASS** | `GET /offline-trust/call-tickets` |
| HMAC binding tags (`btag`) | **PASS** | `OfflineTrustService`, `OfflineTrustStore.kt` |
| NSD advertises `btag` | **PASS** | `NsdLanDiscovery.kt` |
| Offline peer resolution | **PASS** (code) | `OfflinePeerResolver.kt`, `CompositePeerResolver.kt` |
| Local signaling server | **PASS** (code) | `LocalSignalingServer.kt` port 8765 |
| Same-WiFi/no-WAN voice call | **BLOCKED** | Requires two physical devices + WAN disconnect test |

---

## HARDWARE-DEPENDENT GATES

| Test | Status |
|------|--------|
| Keystore persistence | **BLOCKED** |
| Internet P2P two-device call | **BLOCKED** |
| Forced TURN relay call | **BLOCKED** |
| LAN voice call | **BLOCKED** |
| Unknown-peer privacy (3 devices) | **BLOCKED** |
| Wi-Fi Direct voice | **BLOCKED** |
| Microphone / audio routes | **BLOCKED** |

---

## WHAT WAS IMPLEMENTED

### Backend
- `CallSessionService` — Redis-backed call state with participant binding
- Signaling gateway rewrite — typed events, authorization checks
- `CallsService` — callee device resolution, WSS URL in session material
- `OfflineTrustModule` — trust tokens + offline call tickets
- Migration `002_offline_trust.sql`
- Integration test for offline trust material

### Android
- `TokenStore`, `ViroApiClient` — encrypted session + Retrofit
- `CallManager`, `SignalingClient` — WebRTC call orchestration
- `OfflineTrustStore`, `OfflinePeerResolver`, `LocalSignalingServer`
- `btag` in NSD/Wi-Fi Direct advertisements
- Engineering screens: Auth, Call, Diagnostic tabs
- App uses `:voice:webrtc` instead of Linphone stub

### Infrastructure
- `docker-compose.prod.yml` — production-hardened stack
- `infra/proxy/Caddyfile.prod` — HTTPS + WSS reverse proxy

---

## REMAINING FOR PHASE 1A COMPLETION

1. Deploy `docker-compose.prod.yml` on foreign VPS with real secrets and domain
2. Verify `curl https://<domain>/health/live` (no `-k`)
3. Prove coturn allocation + forced relay ICE on two phones
4. Run two-device matrix: Internet P2P → TURN → LAN → no-WAN
5. Keystore hardware test on physical device
6. Capture WebRTC stats screenshots/logs as evidence

---

## RECOMMENDATION

Proceed to **Phase 1A hardware verification** on VPS + two Android phones using the engineering APK (`assembleDebug`). Software path is wired; acceptance gates require real infrastructure and devices.
