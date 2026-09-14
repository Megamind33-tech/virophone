# Architecture Decision Records

## Index

| ADR | Title | Status |
|-----|-------|--------|
| [ADR-001](#adr-001-android-first-modular-monorepo) | Android-first modular monorepo | Accepted |
| [ADR-002](#adr-002-server-authoritative-call-authorization) | Server-authoritative call authorization | Accepted |
| [ADR-003](#adr-003-hmac-hashed-contact-discovery) | HMAC-hashed contact discovery | Accepted |
| [ADR-004](#adr-004-ephemeral-ids-for-local-discovery) | Ephemeral IDs for local discovery | Accepted |
| [ADR-005](#adr-005-calltransport-abstraction) | CallTransport abstraction | Accepted |
| [ADR-006](#adr-006-voiceengine-voip-abstraction) | VoiceEngine VoIP abstraction | Accepted |
| [ADR-007](#adr-007-nestjs-postgresql-typeorm-backend) | NestJS + PostgreSQL + TypeORM backend | Accepted |
| [ADR-008](#adr-008-jwt-access-with-rotating-refresh-tokens) | JWT access with rotating refresh tokens | Accepted |
| [ADR-009](#adr-009-exact-only-viro-id-directory) | Exact-only Viro ID directory | Accepted |
| [ADR-010](#adr-010-stub-first-phase-0-delivery) | Stub-first Phase 0 delivery | Accepted |
| [ADR-011](#adr-011-webrtc-via-stream-webrtc-android) | WebRTC via stream-webrtc-android | Accepted |
| [ADR-012](#adr-012-redis-for-realtime-state) | Redis for realtime state | Accepted |
| [ADR-013](#adr-013-authenticated-wss-signaling) | Authenticated WSS signaling | Accepted |
| [ADR-014](#adr-014-turn-credentials-hmac-sha1) | TURN credentials (HMAC-SHA1 coturn style) | Accepted |
| [ADR-015](#adr-015-128-bit-ephemeral-ids-vr1) | 128-bit ephemeral IDs (`vr1_`) | Accepted |
| [ADR-016](#adr-016-real-nsd-and-wifi-direct-discovery) | Real NSD and Wi-Fi Direct discovery | Accepted |
| [ADR-017](#adr-017-production-fail-closed-config) | Production fail-closed config | Accepted |
| [ADR-018](#adr-018-liblinphone-flexisip-deferred) | Liblinphone/FlexiSIP deferred | Accepted |

---

## ADR-001: Android-first modular monorepo

**Status:** Accepted

**Context:** Viro Reach is a private calling network targeting mobile users first. The team needs shared API contracts, a deployable backend, and a client that can iterate on transport and VoIP independently.

**Decision:** Organize the repository as a monorepo with:

- `apps/android/` — Kotlin multi-module Gradle project (primary client)
- `apps/api/` — NestJS REST API
- `packages/shared-types/` and `packages/api-contracts/` — Cross-platform contracts
- `infra/` — Dockerized dependencies

Android modules split into `core`, `feature`, `transport`, and `voice` layers per `apps/android/settings.gradle.kts`.

**Consequences:**

- (+) Single repo for coordinated API/client changes
- (+) Clear module boundaries enforce dependency direction
- (−) Android builds require SDK; CI uses `android-actions/setup-android`
- (−) iOS deferred; shared types are TypeScript-only today

**References:** `README.md`, `docs/ARCHITECTURE.md`

---

## ADR-002: Server-authoritative call authorization

**Status:** Accepted

**Context:** Private calling requires enforcing blocks, relationships, and privacy settings regardless of client behavior. Local network paths could otherwise attempt direct connection.

**Decision:** Every call must obtain authorization from `POST /api/v1/calls/authorize` before the client selects a transport or starts media. Authorization checks:

1. Block status (symmetric)
2. Phone contact match, accepted Viro connection, or callee Viro ID privacy policy

Implementation: `apps/api/src/calls/calls.service.ts`

Client `CallRouteEngine` documents that authorization precedes route selection.

**Consequences:**

- (+) Consistent policy enforcement; blocked users get generic errors
- (+) Audit trail in `calls` table
- (−) Requires connectivity for call setup (offline LAN-only calling deferred)
- (−) Authorization TTL (5 minutes) must be aligned with signaling in Phase 1

**References:** `docs/SECURITY_MODEL.md`, `docs/CALL_ROUTING.md`

---

## ADR-003: HMAC-hashed contact discovery

**Status:** Accepted

**Context:** Uploading full address books exposes PII and enables mass enumeration. Users expect contact matching similar to other messaging apps with stronger privacy.

**Decision:**

- Client normalizes phones to E.164 and computes `HmacSHA256(phoneE164, CONTACT_HASH_SALT)`
- Server stores `phone_hash` on verified registration; discovery matches incoming hashes only
- Contact names never leave the device
- Batch limit: 200 hashes per request

Client: `ContactDiscoveryService.kt`
Server: `ContactsService.discover`, `hashPhoneForMatching()`

**Consequences:**

- (+) Server never receives raw address book entries in discovery requests
- (+) Returns only hashes the client already knows
- (−) Salt compromise enables offline hash guessing — protect `CONTACT_HASH_SALT`
- (−) Phase 0 uses basic phone normalization; libphonenumber required for production parity

**References:** `docs/DISCOVERY_PRIVACY.md`

---

## ADR-004: Ephemeral IDs for local discovery

**Status:** Accepted

**Context:** LAN/mDNS discovery traditionally exposes hostnames or service names that can identify devices or users. Shared Wi-Fi (offices, cafes) increases passive surveillance risk.

**Decision:**

- Advertise `LocalDiscoveryAdvertisement` with rotating `ephemeralId` (Phase 0.6: `vr1_<url-safe-base64>` — see ADR-015)
- No phone numbers, Viro IDs, or display names in broadcast payload
- Discovered peers stored as `AnonymousPeer` until `AuthorizedPeerResolver` maps ID to a known contact
- Unknown peers visible only as anonymous count, never in UI lists

Implementation: `EphemeralIdGenerator.kt`, `LocalNetworkDiscoveryService.kt`

**Consequences:**

- (+) Strong default privacy on untrusted networks
- (+) Testable via `LocalDiscoveryPrivacyTest.kt`
- (−) Requires secure ephemeral-to-identity binding server protocol (Phase 1)
- (−) ID rotation every 15 minutes may drop in-progress resolution if not handled (ADR-015)

**References:** `docs/DISCOVERY_PRIVACY.md`, diagnostic screen demo

---

## ADR-005: CallTransport abstraction

**Status:** Accepted

**Context:** Viro Reach targets multiple paths: LAN, Wi-Fi Direct, internet SIP, TURN relay, and future mesh/radio. Feature code must not branch on low-level networking APIs.

**Decision:** Define `CallTransport` interface in `:transport:lan` module; implement per-route modules:

| Module | Class | Route |
|--------|-------|-------|
| `:transport:lan` | `LanCallTransport` | LAN |
| `:transport:wifidirect` | `WifiDirectCallTransport` | WIFI_DIRECT |
| `:transport:internet` | `InternetSipCallTransport`, `TurnRelayTransport` | INTERNET_P2P, TURN_RELAY |

`CallRouteEngine` selects transport by fixed priority with availability probes.

**Consequences:**

- (+) Add new transports without changing calling feature logic
- (+) Unit-test routing independent of hardware
- (−) Shared interface in `:transport:lan` is a mild naming coupling
- (−) Phase 0 implementations are stubs

**References:** `docs/CALL_ROUTING.md`

---

## ADR-006: VoiceEngine VoIP abstraction

**Status:** Accepted

**Context:** Linphone SDK is the leading candidate for SIP/media but is AGPL-licensed. The team may need to swap SDKs or run alternate implementations per platform.

**Decision:** All VoIP interaction goes through `VoiceEngine` interface in `:voice:api`. Linphone-specific code lives only in `:voice:linphone` (`LiblinphoneVoiceEngine`).

Phase 0 ships a stub proving state machine and interface completeness without linking SDK.

**Consequences:**

- (+) AGPL isolation for legal review; swap SDK at one module
- (+) UI/features depend on stable Kotlin API
- (−) Maintenance cost of adapter layer
- (−) Advanced SDK features may need interface extensions

**References:** `docs/DEPENDENCY_LICENSES.md`, `LiblinphoneVoiceEngine.kt`

---

## ADR-007: NestJS + PostgreSQL + TypeORM backend

**Status:** Accepted

**Context:** Phase 0 requires relational data (users, sessions, blocks, calls), typed API validation, and fast developer iteration.

**Decision:**

- **NestJS 10** for modular controllers/services, JWT, throttling
- **PostgreSQL 16** as system of record
- **TypeORM** with `synchronize: false` and SQL migrations
- **Redis** provisioned for future rate limiting/session cache

Schema source of truth: `001_initial_schema.sql`

**Consequences:**

- (+) Mature ecosystem; matches team TypeScript contracts package
- (+) Explicit migrations for reviewable schema changes
- (−) TypeORM migration tooling minimal in Phase 0 (manual SQL)
- (−) Redis not yet wired to auth/session layer

**References:** `apps/api/src/app.module.ts`, `docs/DATABASE_SCHEMA.md`

---

## ADR-008: JWT access with rotating refresh tokens

**Status:** Accepted

**Context:** Mobile clients need short-lived access tokens and long-lived sessions without storing long-lived JWTs on device.

**Decision:**

- Access token: JWT (15m default), payload `{ sub, deviceId }`
- Refresh token: opaque `uuid.uuid`, stored as HMAC hash in `sessions`
- Rotation on refresh; previous session row revoked
- Reuse of revoked refresh token revokes entire `family_id` and logs `REFRESH_TOKEN_REUSE`

Implementation: `AuthService`, `JwtStrategy`, `hashRefreshToken()`

**Consequences:**

- (+) Stolen refresh token has limited window; reuse detection limits blast radius
- (+) Device revocation enforced at JWT validation
- (−) No binding of refresh to device signature yet
- (−) Logout revokes sessions but access token valid until expiry

**References:** `docs/SECURITY_MODEL.md`

---

## ADR-009: Exact-only Viro ID directory

**Status:** Accepted

**Context:** Directory search enables harvesting registered IDs and correlating users. Viro ID is an optional public handle with per-user call privacy settings.

**Decision:**

- Expose only `GET /api/v1/directory/exact/:viroId`
- Normalize ID (strip `@`, lowercase, validate pattern)
- Unique index on `profiles.viro_id_normalized`
- No prefix, fuzzy, or list endpoints
- Blocked and self lookups return 404

Implementation: `DirectoryService.exactLookup`

**Consequences:**

- (+) Prevents bulk enumeration via API
- (+) Call privacy default `CONNECTIONS_ONLY` limits cold calls
- (−) Users must share exact handle out-of-band
- (−) Typo in ID yields not-found with no suggestions

**References:** `docs/DISCOVERY_PRIVACY.md`, `viro-id.util.ts`

---

## ADR-010: Stub-first Phase 0 delivery

**Status:** Accepted

**Context:** Full VoIP, signaling, NSD, and production UI require significant integration work. Phase 0 must prove architecture, privacy, and testability without blocking on hardware/SDK completion.

**Decision:** Deliver working stubs for:

| Component | Stub behavior |
|-----------|---------------|
| `LanCallTransport` | Always available; no real NSD |
| `WifiDirectCallTransport` | Reports unavailable |
| `InternetSipCallTransport` / `TurnRelayTransport` | Success without media |
| `LiblinphoneVoiceEngine` | State machine simulation |
| FlexiSIP / coturn | Config files only; not in `make dev` |
| Android UI | `DiscoveryDiagnosticScreen` only |
| Admin | README placeholder |

Acceptance measured by unit tests and API contracts, not live calls.

**Consequences:**

- (+) Validates privacy and authorization early
- (+) CI green without SIP infrastructure
- (−) Risk of stub assumptions diverging from production — Phase 1 must replace stubs incrementally with acceptance tests extended
- (−) Demo does not prove audio quality or NAT traversal

**References:** `docs/ACCEPTANCE_TESTS.md`, `MainActivity.kt`

**Note:** Phase 0.6 supersedes portions of this ADR for NSD, Redis, signaling, and voice — see ADR-011 through ADR-018.

---

## ADR-011: WebRTC via stream-webrtc-android

**Status:** Accepted (Phase 0.6)

**Context:** ADR-006 isolated VoIP behind `VoiceEngine` with a Linphone stub. AGPL licensing and SIP-centric architecture conflict with WebRTC-first signaling delivered in Phase 0.6.

**Decision:** Select `io.getstream:stream-webrtc-android:1.1.3` (Apache 2.0) as the production voice engine in module `:voice:webrtc` (`WebRtcVoiceEngine`). SDP/ICE exchange is application-owned via the signaling gateway.

**Consequences:**

- (+) Permissive license for commercial Android distribution
- (+) Aligns with WSS signaling and TURN credential service
- (−) SIP/FlexiSIP path deprecated for production
- (−) Hardware voice validation not completed

**References:** [VOICE_ENGINE_DECISION.md](VOICE_ENGINE_DECISION.md), `WebRtcVoiceEngine.kt`

---

## ADR-012: Redis for realtime state

**Status:** Accepted (Phase 0.6)

**Context:** ADR-007 provisioned Redis for future use. Phase 0.6 requires ephemeral TTL data, presence, and WebSocket routing without polluting PostgreSQL.

**Decision:** Wire `RedisService` (ioredis) for:

| Key pattern | Purpose | TTL |
|-------------|---------|-----|
| `presence:{userId}` | Online/offline/busy state | 120s |
| `ephemeral:{id}` | Ephemeral ID → user/device | 900s |
| `ws:device:{deviceId}` | Active WSS connection metadata | 3600s |

PostgreSQL remains system of record; Redis is ephemeral coordination layer.

**Consequences:**

- (+) Fast presence and signaling relay
- (+) Health endpoint reports Redis connectivity
- (−) Redis loss requires client re-registration (acceptable — see BACKUP_RESTORE.md)
- (−) No Redis cluster HA configured in Phase 0.6

**References:** `redis.service.ts`, `presence.service.ts`, `discovery.service.ts`, `signaling.gateway.ts`

---

## ADR-013: Authenticated WSS signaling

**Status:** Accepted (Phase 0.6)

**Context:** WebRTC requires SDP and ICE candidate exchange between authorized call participants. FlexiSIP SIP signaling is deferred.

**Decision:** Implement `SignalingGateway` at WebSocket path `/api/v1/signaling/ws`:

- JWT passed as `?token=` query parameter on connect
- Validates user ACTIVE, device not revoked
- `signal` message relays payload to `targetDeviceId` via Redis connection lookup
- Call authorization still required at `POST /calls/authorize` before media

Uses `@nestjs/platform-ws` with `WsAdapter`.

**Consequences:**

- (+) Reuses existing JWT/device revocation model
- (+) No AGPL signaling server in production path
- (−) No dedicated WSS integration test with live clients yet
- (−) Token in query string — ensure TLS-only in production

**References:** `signaling.gateway.ts`, `main.ts`

---

## ADR-014: TURN credentials (HMAC-SHA1 coturn style)

**Status:** Accepted (Phase 0.6)

**Context:** WebRTC NAT traversal requires STUN/TURN. Long-lived TURN passwords in client binaries are unacceptable.

**Decision:** Expose `POST /api/v1/turn/credentials` (JWT required, throttled):

```
username = "{expiry}:{userId}:{deviceId}"
credential = base64(HMAC-SHA1(TURN_SECRET, username))
```

coturn configured with `use-auth-secret` matching `TURN_SECRET`.

**Consequences:**

- (+) Industry-standard coturn temporary credentials
- (+) Per-device accountability in username
- (−) TURN relay not verified on production network (deployment blocked)
- (−) `TURN_SECRET` required in production fail-closed config

**References:** `turn-credential.service.ts`, `infra/coturn/turnserver.conf`

---

## ADR-015: 128-bit ephemeral IDs (`vr1_`)

**Status:** Accepted (Phase 0.6) — supersedes ADR-004 entropy/format details

**Context:** Phase 0 used `vr-eph-<8 hex>` (32-bit entropy). LAN-scale discovery and tracking resistance require higher entropy and server-backed TTL.

**Decision:**

- Format: `vr1_<url-safe-base64>` encoding 128 bits from `SecureRandom`
- Client rotation: 15 minutes (`EphemeralIdGenerator`)
- Server TTL: 900 seconds in Redis
- Register via `POST /api/v1/discovery/ephemeral`; resolve via authorized `POST /api/v1/discovery/ephemeral/resolve`

**Consequences:**

- (+) Collision and tracking resistance materially improved
- (+) Server-mediated authorized resolution implemented
- (−) Offline resolution not implemented (design only)
- (−) Client must re-register after rotation

**References:** [EPHEMERAL_ID_DESIGN.md](EPHEMERAL_ID_DESIGN.md), `EphemeralIdGenerator.kt`, `discovery.service.ts`

---

## ADR-016: Real NSD and Wi-Fi Direct discovery

**Status:** Accepted (Phase 0.6) — supersedes ADR-010 stub for LAN discovery

**Context:** Phase 0 stubbed NSD and reported Wi-Fi Direct unavailable. Privacy design requires real broadcast with ephemeral IDs only.

**Decision:** Implement:

- `NsdLanDiscovery` — Android `NsdManager`, service type `_viroreach._tcp.`, TXT `eid`
- `WifiDirectDiscovery` — `WifiP2pDnsSdServiceInfo` on `_viroreach._tcp`
- Orchestrated by `LocalNetworkDiscoveryService` with `ServerAuthorizedPeerResolver`

**Consequences:**

- (+) Real LAN/Wi-Fi Direct discovery code paths exist
- (+) Privacy tests pass (`LocalDiscoveryPrivacyTest.kt`)
- (−) Hardware validation blocked — runtime permissions not fully implemented
- (−) Simulated peer injection still available on diagnostic screen

**References:** `NsdLanDiscovery.kt`, `WifiDirectDiscovery.kt`, `DiscoveryDiagnosticScreen.kt`

---

## ADR-017: Production fail-closed config

**Status:** Accepted (Phase 0.6)

**Context:** Development defaults (`dev_*`, `change_me`) must never reach production deployments.

**Decision:** Call `validateProductionConfig()` at API bootstrap (`main.ts`). When `NODE_ENV=production`:

- Refuse startup if any of `DATABASE_URL`, `REDIS_URL`, `JWT_ACCESS_SECRET`, `JWT_REFRESH_SECRET`, `CONTACT_HASH_SALT`, `TURN_SECRET`, `EPHEMERAL_SIGNING_SECRET` missing
- Refuse dev fallback pattern values
- Enforce `JWT_ACCESS_SECRET` length ≥ 32

**Consequences:**

- (+) Prevents accidental insecure production boot
- (+) Covered by `production-config.spec.ts`
- (−) Operators must provision all secrets before first deploy

**References:** `production-config.ts`, [DEPLOYMENT.md](DEPLOYMENT.md)

---

## ADR-018: Liblinphone/FlexiSIP deferred

**Status:** Accepted (Phase 0.6)

**Context:** Linphone SDK and FlexiSIP are AGPL. Phase 0.6 delivers WebRTC + WSS signaling without SIP registration.

**Decision:**

- **Defer** Liblinphone SDK integration — `LiblinphoneVoiceEngine` remains stub
- **Remove** FlexiSIP from production deployment path (config stub retained in `infra/flexisip/`)
- **Do not** add FlexiSIP to default `docker-compose.yml` production topology
- Internet calling path: WebRTC + TURN, not `InternetSipCallTransport` SIP

**Consequences:**

- (+) Avoids AGPL distribution blocker for Phase 0.6
- (+) Simpler operational stack (no SIP registrar)
- (−) `CALL_ROUTING.md` SIP sections are legacy relative to WebRTC path
- (−) Commercial Linphone license option remains documented for future evaluation

**References:** [VOICE_ENGINE_DECISION.md](VOICE_ENGINE_DECISION.md), [DEPENDENCY_LICENSES.md](DEPENDENCY_LICENSES.md)

---

## Change process

1. Propose new ADR in pull request with status **Proposed**
2. Review against privacy ([DISCOVERY_PRIVACY.md](DISCOVERY_PRIVACY.md)) and security ([SECURITY_MODEL.md](SECURITY_MODEL.md)) docs
3. On merge, set status **Accepted** and link implementing PRs
4. Superseded decisions remain in document with **Superseded by ADR-NNN**

---

## Related documents

- [ARCHITECTURE.md](ARCHITECTURE.md)
- [ACCEPTANCE_TESTS.md](ACCEPTANCE_TESTS.md)
