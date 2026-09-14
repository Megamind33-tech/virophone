# Architecture Decision Records (Phase 0)

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

- Advertise `LocalDiscoveryAdvertisement` with rotating `ephemeralId` (`vr-eph-<hex>`)
- No phone numbers, Viro IDs, or display names in broadcast payload
- Discovered peers stored as `AnonymousPeer` until `AuthorizedPeerResolver` maps ID to a known contact
- Unknown peers visible only as anonymous count, never in UI lists

Implementation: `EphemeralIdGenerator.kt`, `LocalNetworkDiscoveryService.kt`

**Consequences:**

- (+) Strong default privacy on untrusted networks
- (+) Testable via `LocalDiscoveryPrivacyTest.kt`
- (−) Requires secure ephemeral-to-identity binding server protocol (Phase 1)
- (−) ID rotation every 5 minutes may drop in-progress resolution if not handled

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
