# Ephemeral Discovery ID Design

**Phase:** 0.6 (implemented)  
**Implementation:** `apps/android/feature/discovery/EphemeralIdGenerator.kt`  
**Server registry:** `apps/api/src/discovery/discovery.service.ts`

## Format

```
vr1_<url-safe-base64-no-padding>
```

Example: `vr1_Z9BqQ9VxTestEphemeralId128bitsxxxxxxxx`

| Property | Value |
|----------|-------|
| Prefix | `vr1_` (version tag) |
| Payload | 16 bytes (128 bits) from `SecureRandom` |
| Encoding | URL-safe Base64, no padding |
| Typical length | ~25 characters after prefix |

**Supersedes** Phase 0 format `vr-eph-<8 hex>` (32-bit entropy).

## Generation

```kotlin
val bytes = ByteArray(16) // 128 bits
SecureRandom().nextBytes(bytes)
val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
return "vr1_$encoded"
```

**Not derived from:** phone number, Viro ID, device UUID, MAC, IP, or static device ID.

## Rotation

| Setting | Value | Location |
|---------|-------|----------|
| Client rotation interval | **15 minutes** | `EphemeralIdGenerator.rotationIntervalMs` |
| Server Redis TTL | **900 seconds (15 min)** | `EPHEMERAL_TTL_SECONDS` env / default in `discovery.service.ts` |

- `getCurrentId()` auto-rotates when interval elapsed
- `rotate()` forces immediate new ID (diagnostic screen: "Stop discovery & rotate ephemeral ID")
- Client should re-register with server after rotation via `POST /api/v1/discovery/ephemeral`

## Server-side registry (Redis)

Phase 0.6 implements authenticated ephemeral mapping:

| Redis key | Value | TTL |
|-----------|-------|-----|
| `ephemeral:{ephemeralId}` | `{ ephemeralId, userId, deviceId, expiresAt }` | 900s |
| `ephemeral:device:{deviceId}` | `{ ephemeralId }` (cleanup helper) | 900s |

### API

| Method | Path | Auth | Purpose |
|--------|------|------|---------|
| POST | `/api/v1/discovery/ephemeral` | JWT | Register current ephemeral ID for device |
| POST | `/api/v1/discovery/ephemeral/resolve` | JWT | Resolve ID if requester authorized |

Resolution rules (`discovery.service.ts`):

- Invalid format (`!startsWith('vr1_')` or `length < 20`) → 400
- Unknown or expired ID → `{ authorized: false }`
- Self-lookup → `{ authorized: false }`
- Blocked relationship → `{ authorized: false }`
- Target not in requester's `authorizedUserIds[]` → `{ authorized: false }`
- Success → `{ authorized: true, userId }` (minimal data — no full profile leak)

Client resolver: `ServerAuthorizedPeerResolver.kt` sends locally known authorized user IDs.

## LAN advertisement

Ephemeral IDs appear in discovery payloads only:

### NSD (`NsdLanDiscovery.kt`)

- Service type: `_viroreach._tcp.`
- TXT attribute `eid`: full ephemeral ID
- No phone, name, or Viro ID

### Wi-Fi Direct (`WifiDirectDiscovery.kt`)

- DNS-SD service: `_viroreach._tcp`
- TXT record `eid`: full ephemeral ID
- Ignores IDs not starting with `vr1_` or matching own current ID

## Collision handling

- 128-bit random space: birthday bound ~2^64 devices before 50% collision — acceptable for LAN scale
- On collision, authorized resolution could map to wrong peer — mitigated by server-side userId binding and authorized list check
- Server rejects malformed IDs at registration

## Authentication & replay

| Concern | Phase 0.6 behavior |
|---------|-------------------|
| Replay old ephemeral ID | Redis TTL + client rotation → record expires after 15 min |
| Unauthorized resolution | Requires JWT + `authorizedUserIds` containing target `userId` |
| Blocked users | Resolution returns unauthorized; presence shows OFFLINE |
| Offline binding | **Not implemented** — see [OFFLINE_TRUST_DISCOVERY.md](OFFLINE_TRUST_DISCOVERY.md) |

`EPHEMERAL_SIGNING_SECRET` is required in production config (`production-config.ts`) for future signed ephemeral bindings; not yet used in resolve logic.

## Relationship to device identity

- **Independent** from `DeviceIdentityManager` Keystore keypair
- Ephemeral ID: LAN/Wi-Fi Direct advertisement only
- Device crypto identity: API authentication (JWT / device registration)

## Privacy properties

- Must rotate to prevent LAN tracking (15-minute window)
- Unknown peers: counted in `anonymousPeerCount`, not shown in UI lists (`LocalNetworkDiscoveryService.kt`)
- Diagnostic screen can inject **SIMULATED** peers for demo; real NSD/Wi-Fi Direct paths are **REAL** when permissions granted

## Tests

| Test | File |
|------|------|
| Rotation and format | `LocalDiscoveryPrivacyTest.kt` |
| Server register/resolve | `redis.integration.spec.ts` |
| Unauthorized unknown peer | `phase06.integration.spec.ts` |

## Related documents

- [DISCOVERY_PRIVACY.md](DISCOVERY_PRIVACY.md)
- [OFFLINE_TRUST_DISCOVERY.md](OFFLINE_TRUST_DISCOVERY.md)
- [DECISIONS.md](DECISIONS.md) — ADR-004, ADR-015
