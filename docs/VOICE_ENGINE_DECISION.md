# Voice Engine Decision (ADR)

**Status:** Accepted (Phase 0.6)  
**Date:** 2026-09-14  
**Supersedes:** Linphone-as-default path implied by ADR-006 stub delivery

## Context

Viro Reach needs a voice/media stack for Android that:

1. Implements the existing `VoiceEngine` interface (`apps/android/voice/api`)
2. Works with WebRTC signaling (authenticated WSS at `/api/v1/signaling/ws`) and TURN credentials from the API
3. Avoids AGPL copyleft on the production client distribution path
4. Supports voice-only calls with ICE/STUN/TURN NAT traversal

Three candidates were evaluated:

| Candidate | License | Integration state (Phase 0.6) |
|-----------|---------|-------------------------------|
| **WebRTC via `stream-webrtc-android`** | Apache 2.0 | **Selected** — `WebRtcVoiceEngine` in `:voice:webrtc` |
| **Liblinphone SDK** | AGPL v3 | **Deferred** — stub only in `:voice:linphone` |
| **PJSIP / pjsua2** | GPL v2 | **Not integrated** — evaluated on paper only |

FlexiSIP (AGPL signaling server) is **removed from the production path**. Signaling is handled by the NestJS WebSocket gateway, not SIP registration.

## Decision

**Adopt `io.getstream:stream-webrtc-android:1.1.3` (Apache 2.0) as the production voice engine**, implemented as `WebRtcVoiceEngine` behind the `VoiceEngine` interface.

Liblinphone and FlexiSIP remain in the repository as historical stubs for interface compatibility and licensing documentation, but are **not** on the production build path.

## Comparison

### WebRTC (`stream-webrtc-android`)

**Pros**

- Apache 2.0 — permissive for commercial Android distribution
- Native fit for Phase 0.6 signaling: SDP/ICE exchange over authenticated WSS; TURN via coturn HMAC credentials
- Stream-maintained Android WebRTC binaries reduce build complexity vs compiling `libwebrtc` from source
- `WebRtcVoiceEngine` already wires `PeerConnection`, audio track, and ICE state machine to `CallStateMachineState`

**Cons**

- No built-in SIP stack — legacy `InternetSipCallTransport` remains stubbed; internet path is WebRTC + signaling, not SIP
- Signaling orchestration is application-owned (not handled by SDK)
- Hardware voice quality, codec tuning, and echo cancellation require on-device validation (**not yet tested**)
- Dependency on third-party WebRTC binary updates (Stream fork)

### Liblinphone SDK

**Pros**

- Mature SIP/VoIP stack with media, codecs, and NAT helpers in one SDK
- Existing stub adapter (`LiblinphoneVoiceEngine`) proves interface boundary
- FlexiSIP pairs naturally for SIP registrar use cases

**Cons**

- **AGPL v3** — copyleft applies to combined client distribution; commercial license required for typical closed-source app
- FlexiSIP server is also AGPL — operational licensing burden
- SIP-centric model mismatches Phase 0.6 WebRTC signaling architecture
- SDK integration weight (native libs, ProGuard, codec patents) deferred

### PJSIP

**Pros**

- Widely deployed open-source SIP stack
- Fine-grained control over signaling and media pipelines

**Cons**

- **GPL v2** — generally more restrictive than Apache/MIT for proprietary mobile apps
- No existing Viro Reach adapter module; integration cost similar to Liblinphone
- Same SIP-vs-WebRTC architecture mismatch as Liblinphone for the chosen signaling path
- Would still require separate TURN/STUN and application signaling for WebRTC-first design

## Architecture alignment

```
Call authorize (REST) → sessionMaterial { callId, iceServers }
        ↓
SignalingGateway (WSS) ← SDP / ICE relay between devices
        ↓
WebRtcVoiceEngine → PeerConnection + audio track
        ↓
STUN/TURN (coturn, temp credentials from POST /api/v1/turn/credentials)
```

The `:voice:webrtc` module depends on `:voice:api` only. The app module still references `:voice:linphone` for Phase 0 stub wiring; production wiring should swap to `:voice:webrtc`.

## Consequences

- (+) Clear permissive license path for Play Store distribution
- (+) Aligns client media with server WebSocket signaling and TURN credential service
- (+) `VoiceEngine` abstraction preserved — swap remains possible
- (−) SIP/FlexiSIP documentation and transport stubs are legacy; `CALL_ROUTING.md` SIP sections are stale relative to WebRTC path
- (−) End-to-end voice on physical devices **not verified** in Phase 0.6
- (−) `LiblinphoneVoiceEngine` stub should not be used for new features

## Evidence

| Artifact | Path |
|----------|------|
| WebRTC engine | `apps/android/voice/webrtc/src/main/kotlin/.../WebRtcVoiceEngine.kt` |
| Gradle dependency | `apps/android/voice/webrtc/build.gradle.kts` |
| Linphone stub (deferred) | `apps/android/voice/linphone/.../LiblinphoneVoiceEngine.kt` |
| Signaling | `apps/api/src/signaling/signaling.gateway.ts` |
| TURN credentials | `apps/api/src/turn/turn-credential.service.ts` |

## Related documents

- [DECISIONS.md](DECISIONS.md) — ADR-006 (VoiceEngine abstraction), ADR-011 (WebRTC selection)
- [DEPENDENCY_LICENSES.md](DEPENDENCY_LICENSES.md)
- [CALL_ROUTING.md](CALL_ROUTING.md)
