# Offline Trusted-Contact Discovery

**Status:** DESIGN ONLY (Phase 0.6)  
**Implementation:** None — no production code paths

## Purpose

Define how two Viro Reach users who are already **mutually authorized contacts** could discover each other on a local network **without server connectivity**, while preserving the privacy properties of ephemeral LAN advertisement.

This document is a design specification only. Phase 0.6 implements **online** ephemeral resolution via `POST /api/v1/discovery/ephemeral/resolve` backed by Redis. Offline resolution is explicitly out of scope for Phase 0.6 delivery.

## Problem statement

When the API is unreachable (airplane mode with Wi-Fi, captive portal, regional outage), users on the same LAN may still want to place authorized calls. Current flow requires:

1. Register ephemeral ID: `POST /api/v1/discovery/ephemeral`
2. Resolve peer: `POST /api/v1/discovery/ephemeral/resolve`

Both require authenticated HTTPS. Offline mode needs a **local cryptographic binding** between rotating ephemeral IDs and pre-established trust material.

## Design goals

| Goal | Rationale |
|------|-----------|
| No PII in broadcast | Same as online NSD/Wi-Fi Direct — only `vr1_*` ephemeral IDs |
| Mutual trust required | Only contacts with prior server-authorized relationship |
| No global phone enumeration | Attacker on LAN cannot map ephemeral ID → phone without trust material |
| Forward secrecy on rotation | Old ephemeral IDs must not reveal future IDs |
| Graceful online fallback | Online server resolution remains authoritative when available |

## Proposed crypto primitives

### Trust material (established while online)

When users become authorized contacts (phone match, accepted connection), server issues **per-relationship offline trust tokens** during a future phase:

```
trustToken = HMAC-SHA256(
  key = relationshipSecret,
  message = concat(userIdA, userIdB, epoch)
)
```

Where `relationshipSecret` is derived from:

- Server ECDH key agreement at connection acceptance time, **or**
- HKDF over a server-signed relationship certificate delivered to both clients

Clients store tokens in encrypted local storage (Room + Android Keystore wrapper). Tokens are **never** broadcast.

### Ephemeral advertisement binding (offline)

Each rotation interval (15 minutes, aligned with `EphemeralIdGenerator`):

1. Client generates `ephemeralId = vr1_<128-bit url-safe-base64>` (existing generator).
2. Client computes:

```
bindingTag = HMAC-SHA256(
  key = trustToken,
  message = ephemeralId
)
```

3. NSD/Wi-Fi Direct TXT record advertises:

| Key | Value |
|-----|-------|
| `eid` | Full ephemeral ID (`vr1_...`) |
| `btag` | First 16 bytes of `bindingTag`, base64url-encoded (truncated MAC) |
| `cap` | `voice` |

The truncated MAC allows authorized peers to **candidate-match** without revealing identity to unauthorized observers.

### Local resolution algorithm

For each discovered peer `(eid, btag)`:

1. Iterate locally stored `trustToken` entries for authorized contacts.
2. Compute `HMAC-SHA256(trustToken, eid)` and compare first 16 bytes to `btag`.
3. On match, map to local contact record (name from address book, `userId` from cache).
4. If no match, increment anonymous peer counter only (existing privacy behavior).

### Optional upgrade: signed ephemeral certificate

Stronger binding when brief online connectivity is available:

```
ephemeralCert = Sign(
  deviceSigningKey,
  { ephemeralId, expiresAt, userId }
)
```

Peers verify against cached device public keys from prior online sessions. Requires key rotation and revocation list sync — higher complexity; defer to Phase 1+ evaluation.

## Threat model (offline)

| Threat | Mitigation | Residual risk |
|--------|------------|---------------|
| Passive LAN observer learns identity | Only ephemeral ID + truncated MAC broadcast | Observer cannot link to phone without trust token |
| Active replay of old ephemeral ID | 15-minute rotation + expiry in local cache | Brief replay window within TTL |
| Brute-force `btag` (16-byte truncation) | 128-bit ephemeral ID space; rate-limit local verify | Theoretical collision at LAN scale — increase MAC length if needed |
| Stolen device extracts trust tokens | Keystore-backed storage; OS-level encryption | Rooted device can exfiltrate tokens — same as refresh tokens |
| Unauthorized user joins LAN | No trust token → peer stays anonymous | Cannot initiate authorized call without server `calls/authorize` when online; offline authorize is separate hard problem |

## Interaction with call authorization

**Phase 0.6:** `POST /api/v1/calls/authorize` is server-authoritative and requires connectivity.

**Offline design (future):** Would require either:

- Pre-issued short-lived offline call tickets signed by server while online, **or**
- Deferred authorization with post-connect audit (weak — not recommended)

Offline discovery alone does **not** imply offline call authorization. That remains a distinct design decision.

## Limitations (explicit)

1. **Not implemented** — no `btag` in current NSD TXT records (`NsdLanDiscovery.kt` advertises `eid` only).
2. **No offline trust token API** — server does not issue relationship secrets.
3. **No BLE/NFC proximity channel** — Wi-Fi/LAN only in Phase 0.6.
4. **No PSI** — offline matching is pairwise HMAC over known trust tokens, not private set intersection.
5. **Clock skew** — expiry depends on synchronized clocks or server-issued `expiresAt`.
6. **Multi-device** — one user with multiple devices needs per-device trust token or primary-device policy.
7. **Revocation lag** — blocked contact may remain resolvable offline until trust tokens expire.

## Phase 0.6 implemented alternative (online only)

| Step | Endpoint / component |
|------|---------------------|
| Register ephemeral ID | `POST /api/v1/discovery/ephemeral` → Redis `ephemeral:{id}` TTL 900s |
| Resolve for authorized viewer | `POST /api/v1/discovery/ephemeral/resolve` with `authorizedUserIds[]` |
| Android resolver | `ServerAuthorizedPeerResolver.kt` |

Evidence: `apps/api/src/discovery/discovery.service.ts`, `apps/android/feature/discovery/ServerAuthorizedPeerResolver.kt`.

## Open questions

1. Should offline trust tokens rotate on block/unfriend events via push, or wait for TTL?
2. Maximum offline authorization window acceptable for product/legal review?
3. Is truncated HMAC sufficient, or require full Ed25519 signed ephemeral certs?

## Related documents

- [EPHEMERAL_ID_DESIGN.md](EPHEMERAL_ID_DESIGN.md)
- [DISCOVERY_PRIVACY.md](DISCOVERY_PRIVACY.md)
- [SECURITY_MODEL.md](SECURITY_MODEL.md)
