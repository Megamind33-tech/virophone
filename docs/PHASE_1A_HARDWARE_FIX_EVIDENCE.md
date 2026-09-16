# Phase 1A Physical Hardware Fix — Evidence

**Date:** 2026-09-15  
**Branch:** `cursor/phase06-docs-c131`  
**VPS commit under test:** `9c1c8360d9273e9d93b4e30cc894416ecd3116bb`  
**Production:** `https://reach.viro3.online`

---

## Root cause summary

### Diagnostic screen (NOT_IMPLEMENTED everywhere)

| Hypothesis | Verdict |
| ---------- | ------- |
| A. Old APK installed | **Unlikely** — fingerprint/Keystore UI matches current engineering build |
| B. Built from older commit | **Possible before fix** — Diag now shows `BUILD / Commit` injected at compile time |
| C. Phase 0.6 hardcoded state | **YES — primary cause** — `DiscoveryDiagnosticScreen.kt` had literal `NOT_IMPLEMENTED` for SERVER, WEBSOCKET, ACCOUNT; NSD only flipped to REAL after manual button press |
| D. Impl exists, not wired | **Partial** — `NsdLanDiscovery`, `WifiDirectDiscovery`, `SignalingClient`, `WebRtcVoiceEngine` exist but diagnostic did not probe them |
| E. Wrong flavor | **No** — single debug engineering build |
| F. BuildConfig URLs empty | **No** — `API_BASE_URL` / `WSS_URL` set to VPS in `app/build.gradle.kts` |
| G. ViewModel reads stubs | **N/A** — no ViewModel; Composable used hardcoded strings + `InternetSipCallTransport` stub |
| H. DI wrong impl | **Partial** — diagnostic called `WifiDirectCallTransport` (always `UNAVAILABLE`) instead of `WifiDirectDiscovery` |

### Auth HTTP 400

| Step | Finding |
| ---- | ------- |
| Combined button | **OTP Login + Sync Offline Trust** ran request + verify + trust in one `try/catch` → generic `HTTP 400 Bad Request` |
| Failing request | **`POST /api/v1/auth/otp/verify`** (not offline trust, not contract mismatch on request shape) |
| API contract | **MATCH** — Android sends `phoneE164`, `challengeId`, `code`, `devicePublicKey`, `platform`, `appVersion` per NestJS DTOs |
| Phone `+260971100001` | **Valid E.164** (Zambia mobile) — used in integration tests; `INVALID_E164` not expected |
| OTP `123456` | **Prefilled in engineering UI only** — production `OTP_PROVIDER=console` generates **random** OTP → verify returns `VALIDATION_ERROR` / HTTP 400 |
| Security | **PASS** — arbitrary `any number + 123456` does **not** authenticate on production console provider |

---

## Fixes applied (this pass)

1. **Auth screen split** — REQUEST OTP / VERIFY OTP / SYNC OFFLINE TRUST with per-stage PASS/FAIL
2. **Sanitized debug logging** — method, path, status, error code, requestId (no tokens/secrets)
3. **Diagnostic runtime probes** — health check, auth state, WSS state, WebRTC init, NSD auto-start, Wi-Fi Direct precise states, BUILD commit injection
4. **Removed legacy SIP line** from active diagnostic — shows WebRTC internet transport
5. **Removed OTP `123456` default** — empty field + operator documentation
6. **Server: `OTP_PROVIDER=test` forbidden in production**
7. **Server: `OTP_PROVIDER=hardware-test`** — allowlisted phones only for controlled physical testing

---

## Operator action required (VPS)

Production console OTP cannot reach a physical phone. For hardware auth on device:

```env
OTP_PROVIDER=hardware-test
HARDWARE_TEST_PHONES_E164=+260971100001,+260971100002
HARDWARE_TEST_OTP_CODE=<6-digit code distributed out-of-band>
```

Redeploy API only (`docker compose ... up -d api`). Do not enable `OTP_PROVIDER=test`.

---

## Fresh APK

| Field | Value |
| ----- | ----- |
| Base commit (BuildConfig.GIT_COMMIT) | `9c1c836` |
| Working tree | Uncommitted hardware-fix changes on top of base |
| APK path | `apps/android/app/build/outputs/apk/debug/app-debug.apk` |
| APK size | 52,272,472 bytes (2026-09-15) |
| Version | `0.1.0-phase1a` |
| Build type | debug |
| API URL | `https://reach.viro3.online` |
| WSS URL | `wss://reach.viro3.online/api/v1/signaling/ws` |
| Secrets in APK | **None** (grep: no EPHEMERAL_SIGNING_SECRET / JWT / TURN_SECRET) |

---

## Expected device state (after install + before auth)

- SERVER: CONNECTED (health/live)
- AUTH: NOT AUTHENTICATED
- WEBSOCKET: DISCONNECTED
- WEBRTC ENGINE: READY (after init probe)
- LAN NSD: RUNNING (auto-started on Diag tab)
- WI-FI DIRECT: permission/hardware specific state
- OFFLINE TRUST: NOT SYNCED

## Stop gate (this pass)

Do **not** start Phone B call test until physical phone shows:

- [ ] OTP REQUEST PASS
- [ ] OTP VERIFY PASS (with hardware-test OTP or real provider)
- [ ] OFFLINE TRUST SYNCED
- [ ] WSS CONNECTED from device
- [ ] Diag BUILD commit matches PC build
