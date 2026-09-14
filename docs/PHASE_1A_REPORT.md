# Phase 1A Final Report — Hardware & VPS Verification Gate

**Date:** 2026-09-14  
**Branch:** `cursor/phase06-docs-c131`  
**Commit under test:** post-audit security hardening (device-bound offline trust)  
**APK:** `apps/android/app/build/outputs/apk/debug/app-debug.apk` (52,243,800 bytes, `assembleDebug` clean build)

---

## EXECUTIVE VERDICT

**PHASE 1B: NO-GO**

Software regression suite passes (34 unit + 30 integration, Android build PASS). **No hardware or foreign VPS verification was possible in this environment.** All voice, discovery, Keystore, and production deployment gates remain **BLOCKED** pending physical devices and VPS SSH access.

---

## GATE 1 — OFFLINE TRUST SECRET AUDIT

| Question | Finding |
|----------|---------|
| Where does HMAC signing secret live? | **Server only:** `EPHEMERAL_SIGNING_SECRET` env var (`offline-trust.service.ts`). Required in production via `production-config.ts`. |
| Who can mint trust tokens? | **Server only** via authenticated `GET /offline-trust/material` and `GET /offline-trust/call-tickets`. |
| Android global signing secret? | **NO** — grep of `apps/android` shows no `EPHEMERAL_SIGNING_SECRET`, no server HMAC key. |
| Compromised device mint for another user? | **NO** — minting requires server secret; client only stores server-issued `trustToken`. |
| Android uses trustToken for | `btag` = HMAC-SHA256(**trustToken**, ephemeralId) — uses **issued credential** as key, not global secret. |

**Status: PASS** (after device-binding hardening in this verification pass)

**Fix applied:** Trust tokens and call tickets now bind `deviceId` + `protocolVersion` + expiry + nonce. Android rejects expired material and mismatched `deviceId`.

---

## GATE 2 — OFFLINE TRUST TOKEN REQUIREMENTS

| Binding | Implementation |
|---------|----------------|
| User identity | `userId` in HMAC payload |
| Device identity | `deviceId` in trust token + call ticket (added) |
| Peer identity | `peerUserId` |
| Relationship | Only issued for authorized contacts/connections |
| Expiry | Trust: 86400s (24h); call tickets: 3600s (1h) |
| Nonce | 16-byte random per call ticket |
| Protocol version | `v1` in all payloads |

**Status: PASS**

---

## GATE 3 — REPLAY TEST

Automated in `offline-trust.service.spec.ts`:

| Scenario | Result |
|----------|--------|
| Valid ticket for bound device | PASS |
| Wrong device | REJECTED |
| Wrong peer | REJECTED |
| Tampered bytes | REJECTED |
| Expired ticket | REJECTED |

Call tickets are **not one-time consume** on server (offline design); replay within TTL is possible — documented limitation. Re-sync on reconnect invalidates via new epoch.

**Status: PASS** (with documented replay-within-TTL limitation)

---

## GATE 4 — VPS ACCESS

| Check | Result |
|-------|--------|
| Foreign VPS SSH | **BLOCKED** — no credentials in environment; no linked Cursor environment |
| VM inspected (build agent) | Linux 6.12.94+, 15Gi RAM, 233G free disk |
| Docker | 29.1.3 installed |
| Docker Compose | 2.40.3 |

**Status: BLOCKED**

---

## GATE 5 — PRODUCTION ENVIRONMENT

| Check | Result |
|-------|--------|
| Production `.env` on VPS | **BLOCKED** — no VPS access |
| Fail-closed boot | **PASS** (software) — `production-config.spec.ts` verifies missing secrets refuse startup |

**Status: BLOCKED** (VPS); **PASS** (fail-closed logic)

---

## GATE 6 — DEPLOY

```
docker compose -f docker-compose.prod.yml config  → partial (missing env on VM)
docker compose -f docker-compose.prod.yml build → FAIL (overlay mount error)
docker compose -f docker-compose.prod.yml up -d   → FAIL
```

**Status: BLOCKED** on build VM. `docker-compose.prod.yml` ready for VPS.

---

## GATE 7 — SERVICE ISOLATION

Prod compose design: PostgreSQL and Redis on `viro_internal` network only — no public ports.

**Status: PASS** (design); **BLOCKED** (not deployed)

---

## GATES 8–12 — HTTPS / READINESS / WSS / COTURN / TURN RELAY

| Gate | Status |
|------|--------|
| HTTPS `curl -I https://<domain>/health/live` | **BLOCKED** |
| `/health/ready` | **BLOCKED** |
| WSS authenticated connect | **BLOCKED** |
| coturn unauthenticated fail | **BLOCKED** |
| TURN relay network test | **BLOCKED** |

---

## GATE 13 — APK BUILD

```
cd apps/android && ./gradlew clean assembleDebug
→ BUILD SUCCESSFUL
→ apps/android/app/build/outputs/apk/debug/app-debug.apk (52,243,800 bytes)
```

**Status: PASS**

---

## GATE 14 — ENGINEERING APK CONFIG

Current `API_BASE_URL` in `app/build.gradle.kts`: `http://10.0.2.2:3001` (emulator default).

**Must be overridden to `https://<CADDY_DOMAIN>` before hardware test on VPS.**

WSS derived in `CallManager.connectSignaling()` from API base URL.

**Status: NOT IMPLEMENTED** on device — requires VPS domain + hardware session.

---

## GATES 15–17 — PHYSICAL DEVICES / KEYSTORE

| Gate | Status |
|------|--------|
| Phone A / B setup | **BLOCKED** |
| Account + device identity | **BLOCKED** |
| Keystore persistence | **BLOCKED** |

---

## GATES 18–22 — VOICE CALLS (Internet P2P / TURN)

| Test | Status |
|------|--------|
| Internet P2P call setup | **BLOCKED** |
| Two-way audio / mute / speaker | **BLOCKED** |
| P2P telemetry (ICE stats) | **BLOCKED** |
| Forced TURN relay call | **BLOCKED** |
| TURN failure clean handling | **BLOCKED** |

---

## GATES 23–27 — LAN DISCOVERY / CALLS

| Test | Status |
|------|--------|
| Real NSD on hardware | **BLOCKED** |
| Authorized local resolution | **BLOCKED** |
| Third-device privacy | **BLOCKED — THIRD DEVICE REQUIRED** |
| Same-LAN call | **BLOCKED** |
| WAN removal during call | **BLOCKED** |

---

## GATES 28–35 — OFFLINE (NO INTERNET)

| Test | Status |
|------|--------|
| Offline trust preparation | **BLOCKED** |
| Prove Internet off | **BLOCKED** |
| Offline LAN discovery | **BLOCKED** |
| LocalSignalingServer (8765) used on device | **BLOCKED** |
| Offline call (no VPS/TURN/cellular) | **BLOCKED** |
| Offline call history sync | **BLOCKED** |
| Offline unknown user privacy | **BLOCKED** |
| Expired trust rejection on device | **PASS** (code) — `OfflineTrustStore` filters expired |

---

## GATES 36–38 — WI-FI DIRECT

| Test | Status |
|------|--------|
| Wi-Fi Direct discovery | **BLOCKED** |
| Wi-Fi Direct connection | **BLOCKED** |
| Wi-Fi Direct voice | **BLOCKED** |

---

## GATES 39–41 — SECURITY REGRESSION

| Test | Status |
|------|--------|
| Block test (Alice/Bob) | **PASS** (server integration — phase06) |
| Unauthorized signaling injection | **PASS** (code) — `isParticipant()` validation |
| Revoked device signaling | **PASS** (server) — JWT + WSS connect rejects revoked |

Hardware re-verification: **BLOCKED**

---

## GATES 42–45 — OPS / LOGGING

| Test | Status |
|------|--------|
| Server restart recovery | **BLOCKED** |
| DB backup/restore on VPS | **BLOCKED** |
| Production log review | **BLOCKED** |
| Android Logcat review | **BLOCKED** |

---

## GATE 46 — CALL DURATION

**BLOCKED** — no hardware

---

## GATE 47 — FINAL REGRESSION (automated)

| Suite | Result |
|-------|--------|
| API unit | **34/34 PASS** |
| API integration | **30/30 PASS** |
| Android tests | **PASS** |
| Android assembleDebug | **PASS** |
| API build | **PASS** |
| Docker prod build | **BLOCKED** |

---

## GATE 48 — FINAL STATUS MATRIX

| Requirement | Status | Evidence |
|-------------|--------|----------|
| Offline trust: no APK signing secret | **PASS** | Code audit; no secret in Android |
| Offline trust: device-bound tokens | **PASS** | `offline-trust.service.ts`, unit tests |
| Offline trust replay tests | **PASS** | `offline-trust.service.spec.ts` |
| VPS deployment | **BLOCKED** | No SSH credentials |
| Docker prod stack running | **BLOCKED** | Overlay mount error |
| HTTPS live | **BLOCKED** | No deployed domain |
| WSS production | **BLOCKED** | No VPS |
| coturn operational | **BLOCKED** | No VPS |
| Engineering APK built | **PASS** | `app-debug.apk` 52MB |
| Internet P2P two-device voice | **BLOCKED** | No hardware |
| Forced TURN voice | **BLOCKED** | No hardware |
| Real NSD on device | **BLOCKED** | No hardware |
| Local authorized resolution | **BLOCKED** | No hardware |
| Unknown-peer privacy (3 devices) | **BLOCKED** | Third device required |
| Same-LAN voice | **BLOCKED** | No hardware |
| Same-LAN no-Internet voice | **BLOCKED** | No hardware |
| Wi-Fi Direct voice | **BLOCKED** | No hardware |
| Keystore persistence | **BLOCKED** | No hardware |
| Block/presence/signaling security | **PASS** | Integration tests |

---

## GATE 49 — VOICE MATRIX

### INTERNET P2P
| Field | Value |
|-------|-------|
| call setup | **BLOCKED** |
| candidate type | — |
| two-way audio | **BLOCKED** |
| duration | — |
| result | **BLOCKED** |

### TURN
| Field | Value |
|-------|-------|
| relay forced | **BLOCKED** |
| allocation | **BLOCKED** |
| selected relay candidate | — |
| two-way audio | **BLOCKED** |
| result | **BLOCKED** |

### SAME LAN
| Field | Value |
|-------|-------|
| NSD | **BLOCKED** |
| authorized resolution | **BLOCKED** |
| selected route | — |
| audio | **BLOCKED** |
| result | **BLOCKED** |

### SAME LAN — NO INTERNET
| Field | Value |
|-------|-------|
| Internet confirmed unavailable | **BLOCKED** |
| offline trust | **BLOCKED** |
| local signaling | **BLOCKED** |
| two-way audio | **BLOCKED** |
| result | **BLOCKED** |

### WIFI DIRECT — NO INTERNET
| Field | Value |
|-------|-------|
| discovery | **BLOCKED** |
| group | **BLOCKED** |
| IP connectivity | **BLOCKED** |
| trust | **BLOCKED** |
| signaling | **BLOCKED** |
| audio | **BLOCKED** |
| result | **BLOCKED** |

---

## GATE 50 — PHASE DECISION

**PHASE 1B: NO-GO**

Minimum gates for Phase 1B all require hardware and/or VPS:

- VPS stack, HTTPS, WSS, coturn → **BLOCKED**
- Two-device Internet voice → **BLOCKED**
- Forced TURN → **BLOCKED**
- Real NSD + privacy → **BLOCKED**
- Same-LAN voice → **BLOCKED**
- Same-LAN no-Internet → **BLOCKED** (strategic feature — untested)
- Keystore → **BLOCKED**

### What you need to do next

1. **Provide VPS SSH access** (or deploy manually using `docker-compose.prod.yml` + `Caddyfile.prod`)
2. **Set `API_BASE_URL=https://<domain>`** in Android debug build
3. **Install `app-debug.apk`** on two phones (+ third for privacy test)
4. **Run the gate checklist** and record evidence in this document
5. **Same-WiFi/no-Internet** is the defining test — do not skip

Software is ready for verification. The question *"Does Viro Reach actually work?"* cannot be answered without real phones and a deployed server.
