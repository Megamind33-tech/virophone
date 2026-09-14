# Ephemeral Discovery ID Design

Implementation: `apps/android/feature/discovery/EphemeralIdGenerator.kt`

## Format

`vr-eph-<8 hex chars>` e.g. `vr-eph-d31f84a8`

## Generation

- `SecureRandom` — 4 bytes → 8 hex characters
- **Entropy:** 32 bits (Phase 0; recommend 64+ bits before production LAN scale)
- **Not derived from:** phone number, Viro ID, device UUID, MAC, IP, or static device ID

## Rotation

- Default interval: **5 minutes** (`rotationIntervalMs = 5 * 60 * 1000`)
- `getCurrentId()` auto-rotates when interval elapsed
- `rotate()` forces new ID immediately

## Expiry

- IDs are ephemeral by design; old IDs are not valid after rotation
- No server-side ephemeral registry in Phase 0 (local resolution is stubbed)

## Collision handling

- 32-bit space: collision possible at scale; production should increase entropy to 64+ bits
- On collision, authorized resolution would match wrong peer — mitigated by increasing entropy and server-mediated resolution in Phase 1

## Authentication & replay

- Phase 0: local resolution via `AuthorizedPeerResolver` interface (stub)
- Production: authenticated server or cryptographic binding to device session required before mapping ephemeral ID → user
- Replay of old ephemeral IDs should fail after rotation window

## Relationship to device identity

- **Independent** from `DeviceIdentityManager` Keystore keypair
- Ephemeral ID is for LAN advertisement only; device crypto identity is for API authentication

## Tracking risk

- Must rotate to prevent LAN tracking
- Must not encode stable user or device identifiers
- Diagnostic screen uses **SIMULATED** peers only until NSD is implemented
