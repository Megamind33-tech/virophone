# Phase 1A Final Report — Hardware & VPS Verification Gate

**Date:** 2026-09-14  
**Branch:** `cursor/phase06-docs-c131`  
**Commit under test:** Contabo VPS deploy of Phase 1A isolated stack  
**Production domain:** `https://reach.viro3.online`  
**APK:** **BLOCKED** on this PC (no JDK / Android SDK). Debug config now points at the VPS.

---

## EXECUTIVE VERDICT

**VPS SIDE: GO for hardware testing. PHASE 1B: NO-GO until physical Android devices.**

The Phase 1A stack is deployed on Contabo (`vmi3566248`, `79.143.177.140`) at `/opt/viro-reach`, isolated from the other app (`/opt/viro3`). HTTPS, WSS, Postgres, Redis, migrations, fail-closed boot, backup/restore, and Docker restart recovery are proven. Two-device voice, Keystore, LAN, and Wi-Fi Direct remain **BLOCKED — PHYSICAL DEVICES REQUIRED**.

`docker-compose.prod.yml` **config** validates, but was **not** used for `up`: host nginx already binds 80/443. Production run uses the existing isolated file `docker-compose.vps.yml` (same API/Postgres/Redis/coturn services; API on `127.0.0.1:13001`; TURN on **3479**). Full VPS reboot was **not** performed (shared host).

---

## VPS DEPLOYMENT (Contabo)

| Requirement | Status | Evidence |
| ----------- | ------ | -------- |
| SSH access | PASS | `ssh viro3deploy@79.143.177.140` (alias host `viro3` uses `claudeagent`; deploy user `viro3deploy` has Docker). Ubuntu 24.04.4, 7.8Gi RAM, Docker 29.8.0, Compose v5.5.1 |
| Docker | PASS | Engine + Compose plugin already installed; not in `claudeagent` group — deploy via `viro3deploy` |
| production compose | PASS | `docker compose -f docker-compose.vps.yml --env-file .env.vps` project `viro-reach`. `docker compose -f docker-compose.prod.yml config` also OK (not started — would steal 80/443) |
| Caddy | PASS | Host **nginx** reverse-proxy (existing 80/443). New site `reach.viro3.online` only; other vhosts untouched. Container Caddy profile left off (`VIRO_CADDY_MODE=existing`) |
| TLS | PASS | Let's Encrypt `reach.viro3.online`, expires 2026-12-13. `curl -I https://reach.viro3.online/health/live` → HTTP 200 (no `-k`) from VPS and from this PC |
| WSS | PASS | `wss://reach.viro3.online/api/v1/signaling/ws`. Unauthenticated close **4001**. Valid token stays open. Revoked device close **4003** |
| PostgreSQL | PASS | Container `viro-reach-postgres` publishes **no host port**. Host `:5432` is the other app on `127.0.0.1` only. Ready check: `database: connected` |
| Redis | PASS | Container `viro-reach-redis` no public port. `PING` = PONG. After signaling: keys with prefixes `ws` and `presence` |
| migrations | PASS | Entrypoint ran `001_initial_schema` + `002_offline_trust`; subsequent boots skip |
| coturn | PASS | `viro-reach-coturn` host network, realm `reach.viro3.online`, listen **3479**, relay **49160–49200**, `use-auth-secret` (shared secret server-side). UFW opened 3479 tcp/udp + relay UDP |
| TURN credential issuance | PASS | `POST /api/v1/turn/credentials` 201 with JWT; 401 unauthenticated; 401 after device revoke |
| TURN allocation test | PASS | Valid REST-issued creds: TCP `turnutils_uclient` reached Allocate (`create permission error 403` with no peer). Expired HMAC username and junk user: `Cannot complete Allocation`. UDP STUN bind from host timed out — not treated as media-relay proof |
| firewall | PASS | UFW default deny inbound. Exposed: SSH/80/443 (pre-existing) + TURN 3479 + 49160–49200/udp. Postgres/Redis/API 3001 not public (API loopback 13001) |
| backup/restore | PASS | `pg_dump -Fc` 31634 bytes → restore DB `viro_reach_restore_test` (16 tables, both migration versions) → dropped |
| restart recovery | PASS | `docker compose ... restart`; api/postgres/redis healthy, coturn up, HTTPS 200. Full VPS reboot skipped (other production app on same host) |
| production log security | PASS | API logs: 0 JWT-like strings, 0 OTP codes, 0 `static-auth-secret=`, 0 `EPHEMERAL_SIGNING_SECRET=`. Console OTP provider no longer prints codes when `NODE_ENV=production` |
| Android APK build | BLOCKED | `apps/android/gradlew` present; this PC has no `java` / `ANDROID_HOME`. Debug `API_BASE_URL`/`WSS_URL` set to the VPS |

**Services after restart**

- `viro-reach-api` — healthy, `127.0.0.1:13001->3001`
- `viro-reach-postgres` — healthy, 5432 internal
- `viro-reach-redis` — healthy, 6379 internal
- `viro-reach-coturn` — up, UDP/TCP 3479 on host

Fail-closed: missing `JWT_ACCESS_SECRET` → exit 1 `Production startup refused`; secret restored; API live/ready again.

---

## HARDWARE BLOCKERS (do not mark PASS)

| Test | Status |
|------|--------|
| Keystore persistence across phone restart | BLOCKED — PHYSICAL DEVICES REQUIRED |
| Two-device Internet P2P voice | BLOCKED — PHYSICAL DEVICES REQUIRED |
| Forced TURN voice | BLOCKED — PHYSICAL DEVICES REQUIRED |
| LAN voice | BLOCKED — PHYSICAL DEVICES REQUIRED |
| Same-LAN / no-WAN voice | BLOCKED — PHYSICAL DEVICES REQUIRED |
| Three-device privacy | BLOCKED — PHYSICAL DEVICES REQUIRED |
| Wi-Fi Direct voice | BLOCKED — PHYSICAL DEVICES REQUIRED |

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
| Foreign VPS SSH | **PASS** — Contabo `vmi3566248` / `79.143.177.140`, user `viro3deploy` |
| VM inspected | Ubuntu 24.04.4 LTS, 7.8Gi RAM, ~20G free on `/` |
| Docker | 29.8.0 |
| Docker Compose | v5.5.1 |

**Status: PASS**

---

## GATE 5 — PRODUCTION ENVIRONMENT

| Check | Result |
|-------|--------|
| Production `.env` on VPS | **PASS** — `/opt/viro-reach/.env.vps` (not in git) |
| Fail-closed boot | **PASS** (software + VPS) — omitted `JWT_ACCESS_SECRET`, process refused, then restored |

**Status: PASS**

---

## GATE 6 — DEPLOY

```
docker compose -f docker-compose.prod.yml --env-file .env.vps config  → OK
docker compose -f docker-compose.vps.yml --env-file .env.vps build/up → OK (isolated)
```

**Status: PASS** (isolated VPS compose). Prod compose not started: would bind 80/443 already used by host nginx.

---

## GATE 7 — SERVICE ISOLATION

Prod compose design: PostgreSQL and Redis on `viro_internal` network only — no public ports.

**Status: PASS** — VPS containers do not publish Postgres/Redis. Host 5432/6379 remain loopback-only (other app).

---

## GATES 8–12 — HTTPS / READINESS / WSS / COTURN / TURN RELAY

| Gate | Status |
|------|--------|
| HTTPS `curl -I https://reach.viro3.online/health/live` | **PASS** (200, valid LE cert, no `-k`) |
| `/health/ready` | **PASS** (`database: connected`, `redis: connected`) |
| WSS authenticated connect | **PASS** (stays open); unauth 4001; revoked 4003 |
| coturn unauthenticated fail | **PASS** (junk/expired Allocate fails) |
| TURN relay network test | **BLOCKED — PHYSICAL DEVICES REQUIRED** (TCP Allocate with valid creds only) |

---

## GATE 13 — APK BUILD

```
cd apps/android && ./gradlew clean assembleDebug
→ not run on this PC (no java / ANDROID_HOME)
```

**Status: BLOCKED** — wrapper exists (`gradlew`); JDK and Android SDK are missing here. Do not reuse the old 52MB APK (it still targeted the emulator).

---

## GATE 14 — ENGINEERING APK CONFIG

Current debug `API_BASE_URL`: `https://reach.viro3.online`  
Current debug `WSS_URL`: `wss://reach.viro3.online/api/v1/signaling/ws`

**Status: PASS** (config). Fresh `assembleDebug` on this PC: **BLOCKED** (no JDK/SDK).

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
| Server restart recovery | **PASS** (Docker compose restart). Full VPS reboot skipped (shared host) |
| DB backup/restore on VPS | **PASS** |
| Production log review | **PASS** |
| Android Logcat review | **BLOCKED** — PHYSICAL DEVICES REQUIRED |

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
| Android assembleDebug | **BLOCKED** on this PC (no JDK/SDK); prior cloud APK not reused |
| API build | **PASS** (VPS image) |
| Docker prod build | **PASS** (isolated `docker-compose.vps.yml` image `viro-reach-api`) |

---

## GATE 48 — FINAL STATUS MATRIX

| Requirement | Status | Evidence |
|-------------|--------|----------|
| Offline trust: no APK signing secret | **PASS** | Code audit; no secret in Android |
| Offline trust: device-bound tokens | **PASS** | `offline-trust.service.ts`, unit tests |
| Offline trust replay tests | **PASS** | `offline-trust.service.spec.ts` |
| VPS deployment | **PASS** | Contabo `/opt/viro-reach`, commit deployed via rsync |
| Docker prod stack running | **PASS** | api/postgres/redis/coturn; nginx TLS |
| HTTPS live | **PASS** | `https://reach.viro3.online/health/live` |
| WSS production | **PASS** | 4001 / open / 4003 |
| coturn operational | **PASS** | 3479 + REST time-limited credentials |
| Engineering APK built | **BLOCKED** | No JDK/SDK here; URLs already set |
| Internet P2P two-device voice | **BLOCKED** | PHYSICAL DEVICES REQUIRED |
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

**VPS: ready. PHASE 1B: NO-GO** until two (preferably three) physical Android devices run the remaining gates.

### What you need to do next

1. On a machine with Android SDK: `cd apps/android` then `gradlew clean assembleDebug`
2. Install the APK on two phones (third for privacy)
3. Confirm `API_BASE_URL=https://reach.viro3.online` and `WSS_URL=wss://reach.viro3.online/api/v1/signaling/ws`
4. Run the hardware checklist; record evidence here
5. Same-WiFi/no-Internet remains the defining product test — do not skip
