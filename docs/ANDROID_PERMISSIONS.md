# Android Permissions (Phase 0.6)

## Overview

Permissions are declared in:

```
apps/android/app/src/main/AndroidManifest.xml
```

Runtime permission requests are **not fully implemented** in Phase 0.6 (diagnostic screen only). Real NSD (`NsdLanDiscovery`) and Wi-Fi Direct (`WifiDirectDiscovery`) discovery require location/nearby-Wi-Fi permissions at runtime on physical devices — **not verified on hardware in Phase 0.6**.

This document explains each declared permission, Android version behavior, Phase 0.6 usage, and user-facing rationale for production.

**Target SDK:** 34 (`compileSdk = 34` in Gradle modules)

**Min SDK:** 26 (voice module; app module follows same baseline)

---

## Permission reference

| Permission | maxSdkVersion | Android versions | Phase 0.6 usage | Why Viro Reach needs it |
|------------|---------------|------------------|-----------------|-------------------------|
| `INTERNET` | — | All | **Active** — REST API, WSS signaling, TURN | API calls (`ViroApiService`), WebRTC signaling, TURN |
| `ACCESS_NETWORK_STATE` | — | All | **Active** — route engine | Detect connectivity before route selection (`CallRouteEngine`) |
| `ACCESS_WIFI_STATE` | — | All | **Active** — NSD + Wi-Fi Direct | Inspect Wi-Fi for LAN discovery and Wi-Fi Direct readiness |
| `CHANGE_WIFI_STATE` | — | All | **Active** — Wi-Fi Direct | Wi-Fi Direct group owner / service advertisement |
| `ACCESS_FINE_LOCATION` | — | All | **Required at runtime** for NSD/Wi-Fi Direct on many devices | Android requires location for Wi-Fi scan on API 23+ |
| `NEARBY_WIFI_DEVICES` | — | API 33+ (Tiramisu) | **Preferred** on API 33+ with `neverForLocation` | Discover nearby Wi-Fi devices without coarse location |
| `READ_CONTACTS` | — | All | **Implemented** — `ContactDiscoveryService` | Local address book for server-side contact matching |
| `RECORD_AUDIO` | — | All | **Declared** — `WebRtcVoiceEngine` (not hardware-tested) | Voice calls (`VoiceEngine`, microphone capture) |
| `MODIFY_AUDIO_SETTINGS` | — | All | **Declared** — audio routing API | Route audio to earpiece, speaker, Bluetooth |
| `BLUETOOTH` | 30 | API ≤ 30 | **Declared** | Legacy Bluetooth headset routing |
| `BLUETOOTH_CONNECT` | — | API 31+ (S) | **Declared** | Connect to Bluetooth audio devices for calls |

---

## Detailed explanations

### INTERNET

**Manifest line:**

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

**User explanation:** "Viro Reach connects to our servers to verify your account, find contacts who use Viro Reach, and authorize calls."

**Notes:** Normal permission (granted at install). Required for Retrofit API client in `apps/android/core/network/`.

---

### ACCESS_NETWORK_STATE

**User explanation:** "Lets Viro Reach check whether you're online and choose the best way to connect a call."

**Used by:** Transport availability checks, offline detection before call authorization retry.

---

### ACCESS_WIFI_STATE

**User explanation:** "Lets Viro Reach see whether Wi-Fi is available for faster local calling on the same network."

**Used by:** `NsdLanDiscovery` (real NSD on `_viroreach._tcp.`), `LanCallTransport` availability checks.

---

### CHANGE_WIFI_STATE

**User explanation:** "Lets Viro Reach set up direct device-to-device connections when you're not on the same router."

**Used by:** `WifiDirectDiscovery` (real DNS-SD on `_viroreach._tcp`), `WifiDirectCallTransport`.

**Notes:** Protected permission; user does not grant at runtime, but Wi-Fi Direct flows may show system dialogs. Hardware validation **BLOCKED** in Phase 0.6.

---

### ACCESS_FINE_LOCATION

**User explanation:** "Android requires location permission for Wi-Fi-based features. Viro Reach does not track your GPS location — this is only used to discover nearby calling options on your local network."

**Android behavior:**

- Required for Wi-Fi scan results on API 23+
- Wi-Fi Direct discovery historically tied to location permission

**Privacy alignment:** Local discovery uses ephemeral IDs only ([DISCOVERY_PRIVACY.md](DISCOVERY_PRIVACY.md)). Do not log or upload GPS coordinates.

**Runtime:** `dangerous` — must request at runtime before NSD/Wi-Fi Direct discovery on API ≤ 32 or when `NEARBY_WIFI_DEVICES` unavailable.

**Phase 0.6:** Diagnostic screen starts discovery without runtime permission flow — expect discovery failure on physical devices until permission UX is implemented.

---

### NEARBY_WIFI_DEVICES

**Manifest line:**

```xml
<uses-permission android:name="android.permission.NEARBY_WIFI_DEVICES"
    android:usesPermissionFlags="neverForLocation" />
```

**Introduced:** API 33 (Android 13)

**User explanation:** "Lets Viro Reach find other Viro Reach devices nearby over Wi-Fi without using your location."

**Flag `neverForLocation`:** Declares that nearby Wi-Fi device access is not used to derive physical location — reduces need to pair with fine location on API 33+ when applicable.

**Runtime:** `dangerous` on API 33+

---

### READ_CONTACTS

**User explanation:** "Viro Reach reads your contacts on your phone to find people you know who also use Viro Reach. Only secure hashes of phone numbers are sent to our servers — contact names stay on your device."

**Implementation:** `ContactDiscoveryService` hashes locally; see [DISCOVERY_PRIVACY.md](DISCOVERY_PRIVACY.md).

**Runtime:** `dangerous` — request before discovery flow.

**Data handling:** Names never uploaded; only HMAC-SHA256 hashes in batches up to 200.

---

### RECORD_AUDIO

**User explanation:** "Viro Reach needs microphone access so people can hear you on calls."

**Runtime:** `dangerous` — request before first call.

**Used by:** `WebRtcVoiceEngine` (`:voice:webrtc`). End-to-end microphone capture **not verified on hardware** in Phase 0.6.

---

### MODIFY_AUDIO_SETTINGS

**User explanation:** "Lets Viro Reach switch audio between your phone earpiece, speaker, and Bluetooth devices during calls."

**Runtime:** Normal permission (granted at install).

**Used by:** `VoiceEngine.setSpeaker()`, `setAudioRoute()`.

---

### BLUETOOTH (maxSdkVersion="30")

**Manifest line:**

```xml
<uses-permission android:name="android.permission.BLUETOOTH"
    android:maxSdkVersion="30" />
```

**User explanation:** "Lets Viro Reach route call audio to Bluetooth headsets on older Android versions."

**Notes:** Deprecated for connection on API 31+; limited to API 30 and below via `maxSdkVersion`.

---

### BLUETOOTH_CONNECT

**Introduced:** API 31 (Android 12)

**User explanation:** "Lets Viro Reach use your Bluetooth headset or car audio for calls."

**Runtime:** `dangerous` on API 31+

**Used by:** `AudioRoute.BLUETOOTH` in `VoiceEngine`.

---

## Discovery service type

Both LAN and Wi-Fi Direct advertise:

```
_viroreach._tcp.
```

TXT record `eid` contains ephemeral ID only (`vr1_*`). See [EPHEMERAL_ID_DESIGN.md](EPHEMERAL_ID_DESIGN.md).

## Permissions not declared (Phase 0.6)

| Permission | Why omitted |
|------------|-------------|
| `CAMERA` | Voice-only Phase 0; video (`TransportCapability.video`) not active |
| `READ_PHONE_STATE` | Not required for VoIP-only client |
| `POST_NOTIFICATIONS` | API 33+; incoming call notifications in Phase 1 |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_PHONE_CALL` | Background call handling Phase 1 |
| `CHANGE_NETWORK_STATE` | Not required for current discovery implementation |
| `ACCESS_COARSE_LOCATION` | Prefer `NEARBY_WIFI_DEVICES` + `neverForLocation` on API 33+ |
| `USE_FULL_SCREEN_INTENT` | Incoming call UI Phase 1 |

---

## Application security settings

From `AndroidManifest.xml`:

| Setting | Value | Rationale |
|---------|-------|-----------|
| `android:allowBackup` | `false` | Prevent ADB backup of tokens and keys |
| `android:networkSecurityConfig` | `@xml/network_security_config` | TLS/cleartext policy |

Device keys: `DeviceIdentityManager` stores private keys in Android Keystore — not exportable.

---

## Runtime permission strategy (production)

Recommended request order:

1. **Onboarding** — None until feature needed
2. **Contact discovery** — `READ_CONTACTS`
3. **Local calling setup** — `NEARBY_WIFI_DEVICES` (API 33+) or `ACCESS_FINE_LOCATION` (older)
4. **First call** — `RECORD_AUDIO`, `BLUETOOTH_CONNECT` (if Bluetooth route offered)

Use rationale strings from `apps/android/app/src/main/res/values/strings.xml` (extend in Phase 1).

---

## Play Store Data Safety alignment

| Data type | Collected | Encrypted in transit | Purpose |
|-----------|-----------|----------------------|---------|
| Phone number | Yes (user-provided at signup) | Yes | Account verification |
| Contacts | Hashed identifiers only | Yes | Find friends |
| Audio | During calls | Yes (media path) | Voice calling |
| Location | Not collected as GPS | — | Wi-Fi APIs only |

---

## Related documents

- [DISCOVERY_PRIVACY.md](DISCOVERY_PRIVACY.md)
- [SECURITY_MODEL.md](SECURITY_MODEL.md)
- [ARCHITECTURE.md](ARCHITECTURE.md)
