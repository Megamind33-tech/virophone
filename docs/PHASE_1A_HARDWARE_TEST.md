# Phase 1A hardware test sheet

Use this beside two Android phones. Do not start with the offline test. Prove each simpler layer first.

**APK:** debug engineering build only (`0.1.0-phase1a`). Not a release APK.  
**API:** `https://reach.viro3.online`  
**WSS:** `wss://reach.viro3.online/api/v1/signaling/ws`  
**TURN:** `reach.viro3.online:3479` (UDP + TCP). No TURN TLS (`turns:`) in this phase.  
**Engineering UI:** Auth / Call / Diag. ICE AUTO, Force UDP, Force TCP, Refresh ICE stats.

**OTP (production VPS):** Phase 1A uses `OTP_PROVIDER=hardware-test` for **exactly two** engineering phones: `+260961582985` (Phone A) and `+260977426940` (Phone B). The verification credential is configured **only on the VPS** (`HARDWARE_TEST_OTP_CODE`) and entered manually in the APK — it is never returned by REQUEST OTP and never embedded in the APK. Never use `OTP_PROVIDER=test` in production.

**Auth flow on device:** Use separate buttons — REQUEST OTP → enter code → VERIFY OTP / LOGIN → SYNC OFFLINE TRUST → Connect WSS (Diag or Call tab). Check Diag tab for BUILD commit to confirm APK freshness.

**VPS operator (hardware-test OTP):** Set in `/opt/viro-reach/.env.vps` (outside Git):

```
OTP_PROVIDER=hardware-test
HARDWARE_TEST_PHONES_E164=+260961582985,+260977426940
HARDWARE_TEST_OTP_CODE=<credential distributed out-of-band — VPS only>
```

Non-allowlisted numbers must receive **403 Forbidden**. Never commit the OTP code.

**Phone A stop gate (before Phone B):** Diag must show SERVER=CONNECTED, AUTH=AUTHENTICATED, DEVICE=REGISTERED (Auth tab), WEBSOCKET=CONNECTED, WEBRTC=READY, OFFLINE TRUST=SYNCED, LAN NSD=RUNNING (when Wi-Fi on), LOCAL SIGNALING=LISTENING (or CONNECTED). If any fail, stop.

**Diag fields (Gate 2):** SIGNALING ROUTE (NONE/WSS/LOCAL_LAN/…), LOCAL SIGNALING (STOPPED/LISTENING/AUTHENTICATING/CONNECTED/FAILED), MEDIA ROUTE (NONE/HOST/SRFLX/RELAY during active call).

**Local signaling:** `CallManager` now uses `CallSignalingTransport` (WSS + LAN). **SAME-LAN NO-WAN = NOT IMPLEMENTED (hardware)** until physically proven with WAN disconnected.

Status words only: **PASS** / **FAIL** / **BLOCKED** / **NOT IMPLEMENTED** / **DESIGN ONLY**.

Install (from a PC with platform-tools):

```powershell
adb devices
adb -s <serial-A> install -r <apk-path>
adb -s <serial-B> install -r <apk-path>
```

Do not record IMEI or serial in this sheet.

---

## Phone A

| Field | Value |
| ----- | ----- |
| Android version | |
| Build installed | `0.1.0-phase1a` debug |
| Network (per test) | |
| Keystore fingerprint abbreviation (first 24 chars) | |

## Phone B

| Field | Value |
| ----- | ----- |
| Android version | |
| Build installed | `0.1.0-phase1a` debug |
| Network (per test) | |
| Keystore fingerprint abbreviation (first 24 chars) | |

## Phone C (optional)

| Field | Value |
| ----- | ----- |
| Android version | |
| Build installed | |
| Known to A? | No |
| Keystore fingerprint abbreviation | |

If Phone C is unavailable: Test 8 = `BLOCKED — THIRD DEVICE REQUIRED`.

---

## Test 1 — Authentication on both devices

Order: do this first.

1. Auth tab. Request OTP, enter code, **OTP Login + Sync Offline Trust**.
2. Repeat on the other phone with a different account.
3. Confirm WSS later via Call tab **Connect WSS**.

| Check | A | B |
| ----- | - | - |
| Login | | |
| Offline trust sync | | |
| Result | | |

---

## Test 2 — Keystore fingerprint persistence

On **one** phone:

| Step | Expected | Result |
| ---- | -------- | ------ |
| Record fingerprint abbreviation | Auth tab shows first 24 chars | |
| Restart app | Same fingerprint | |
| Restart phone | Same fingerprint | |
| Clear app data | New fingerprint | |
| Register again | Login succeeds | |
| Revoke prior device (server) | Old credentials fail | |

---

## Test 3 — Different-network Internet P2P call

Phone A: Wi-Fi. Phone B: mobile data or a different network.

Call tab: **ICE AUTO**. A authorizes B. B rings, B answers.

| Check | Result |
| ----- | ------ |
| A authorizes B | |
| B rings | |
| B answers | |
| Two-way audio | |
| Mute | |
| Speaker | |
| Hangup | |
| History | |
| ICE stats (`Refresh ICE stats`) | |
| Selected candidate type | |

Record ICE line exactly (example: `local=srflx remote=srflx proto=udp`).

---

## Test 4 — Forced TURN UDP call

Call tab: **Force UDP**. Same split networks as Test 3.

Expected candidate type: `relay`. Protocol: `udp`. Two-way audio.

| Check | Result |
| ----- | ------ |
| Relay mode | TURN UDP |
| ICE local type | |
| ICE remote type | |
| Protocol | |
| Two-way audio | |
| Result | |

UDP STUN PASS on the VPS does not imply this PASS. Record independently.

---

## Test 5 — Forced TURN TCP fallback

Call tab: **Force TCP**. Where the device stack supports TCP TURN.

| Check | Result |
| ----- | ------ |
| Relay mode | TURN TCP |
| ICE local type | |
| Protocol | |
| Two-way audio | |
| Result | |

If UDP FAIL and TCP PASS: record both. Do not label TURN fully healthy.

---

## Test 6 — Same-LAN discovery

Both phones on the same Wi-Fi. Diag tab.

Expected: NSD detects an anonymous service. Authorized resolver identifies only the known contact.

| Check | Result |
| ----- | ------ |
| Anonymous NSD service seen | |
| Authorized resolver identifies known contact only | |
| Result | |

---

## Test 7 — Same-LAN voice

Same Wi-Fi. ICE AUTO. Call connects. Determine route from ICE stats, not UI label alone.

| Check | Result |
| ----- | ------ |
| Call connects | |
| Two-way audio | |
| ICE stats | |
| Actual route | |
| Result | |

---

## Test 8 — Unknown-peer privacy (third device)

A knows B. A does not know C. B and C advertise on LAN.

| Check | Result |
| ----- | ------ |
| A may detect two anonymous services | |
| A resolves only B | |
| C remains unidentified | |
| Result | |

If no third phone: `BLOCKED — THIRD DEVICE REQUIRED`.

---

## Test 9 — Same LAN, WAN disconnected, mobile data disabled

Do this only after Tests 1–7 (online trust material already synced).

1. Disable mobile data on both phones.
2. Disconnect router WAN.
3. Confirm `https://reach.viro3.online` is unreachable.

Then: local discovery, offline resolution, local signaling, call, two-way audio, hangup, offline history.

No foreign VPS may participate.

| Check | Result |
| ----- | ------ |
| API unreachable | |
| Local discovery | |
| Offline resolution | |
| Local signaling used (`LocalSignalingServer` or equivalent) | |
| Arbitrary LAN device cannot ring port 8765 | |
| Call + two-way audio | |
| Hangup + offline history | |
| Result | |

Current software: `LocalSignalingServer` exists but is **not wired** into CallManager. If the APK still has no local signaling path, mark **NOT IMPLEMENTED** and stop this test. Do not invent a PASS from LAN discovery alone.

---

## Test 10 — Wi-Fi Direct discovery

| Check | Result |
| ----- | ------ |
| Discovery | |
| Result | |

---

## Test 11 — Wi-Fi Direct voice

| Check | Result |
| ----- | ------ |
| Group / IP | |
| Trust | |
| Two-way audio | |
| Result | |

---

## TURN / ICE notes (do not collapse)

Record separately:

```text
UDP STUN
UDP TURN allocation
TCP TURN allocation
WebRTC TURN media
```

A PASS in one does not imply PASS in the others.

TURN TLS (`turns:`): **NOT IMPLEMENTED** in production coturn (`no-tls` / `no-dtls`).
