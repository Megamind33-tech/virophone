# Phase 0 Acceptance Tests

## Purpose

This document defines acceptance criteria for Viro Reach Phase 0 and maps them to automated test coverage in the repository. Phase 0 validates architecture, privacy rules, and server-authoritative security — not end-to-end voice quality or production UX.

**Verification commands:**

```bash
make bootstrap   # Full setup + API tests
make test        # API + Android unit tests
make health      # API liveness/readiness
```

CI pipeline: `.github/workflows/ci.yml`

---

## Acceptance criteria

### AC-1: Monorepo builds and bootstraps

| ID | Criterion | Status |
|----|-----------|--------|
| AC-1.1 | `make bootstrap` installs packages, starts Postgres, runs migrations, builds API, runs tests | Required |
| AC-1.2 | `make dev` starts infra and API on port 3001 | Required |
| AC-1.3 | `GET /health/live` returns `{ status: "ok" }` | Required |
| AC-1.4 | `GET /health/ready` reports database connected after migrate | Required |
| AC-1.5 | Android `./gradlew assembleDebug test` passes when SDK available | Required |

**Evidence:** `scripts/bootstrap.sh`, `Makefile`, `.github/workflows/ci.yml`

---

### AC-2: Database schema

| ID | Criterion | Status |
|----|-----------|--------|
| AC-2.1 | Migration `001_initial_schema.sql` creates all Phase 0 tables | Required |
| AC-2.2 | Foreign keys and unique constraints match [DATABASE_SCHEMA.md](DATABASE_SCHEMA.md) | Required |
| AC-2.3 | `npm run migration:run` is idempotent via `schema_migrations` tracking | Required |

**Evidence:** `apps/api/src/database/migrations/001_initial_schema.sql`

---

### AC-3: Authentication and sessions

| ID | Criterion | Status |
|----|-----------|--------|
| AC-3.1 | OTP request validates E.164 format | Required |
| AC-3.2 | OTP codes stored as HMAC hashes, not plaintext | Required |
| AC-3.3 | Successful verify returns access + refresh tokens and device ID | Required |
| AC-3.4 | Refresh token rotation revokes previous session | Required |
| AC-3.5 | Refresh token reuse revokes entire session family | Required |
| AC-3.6 | JWT validation rejects revoked devices and non-ACTIVE users | Required |

**Automated tests:**

| Test | File |
|------|------|
| Refresh token hashing consistency | `apps/api/src/auth/auth.service.spec.ts` |
| OTP hash length | `apps/api/src/auth/auth.service.spec.ts` |

**Manual verification:**

```bash
# Request OTP (check API console for code)
curl -X POST http://localhost:3001/api/v1/auth/otp/request \
  -H 'Content-Type: application/json' \
  -d '{"phoneE164":"+15551234567"}'
```

---

### AC-4: Contact discovery privacy

| ID | Criterion | Status |
|----|-----------|--------|
| AC-4.1 | Discovery accepts max 200 phone hashes per request | Required |
| AC-4.2 | Only submitted hashes are evaluated — no full user enumeration | Required |
| AC-4.3 | Blocked users excluded from discovery results | Required |
| AC-4.4 | User cannot discover themselves | Required |
| AC-4.5 | Client sends hashes only; names remain on device | Required |
| AC-4.6 | Large batches log `SUSPICIOUS_ENUMERATION` event | Required |

**Automated tests:**

| Test | File |
|------|------|
| Empty matches | `apps/api/src/contacts/contacts.service.spec.ts` |
| Submitted hash only | `apps/api/src/contacts/contacts.service.spec.ts` |
| Block exclusion | `apps/api/src/contacts/contacts.service.spec.ts` |
| Self exclusion | `apps/api/src/contacts/contacts.service.spec.ts` |
| Batch limit 201 rejected | `apps/api/src/contacts/contacts.service.spec.ts` |
| Phone normalization | `apps/android/feature/contacts/.../PhoneNormalizerTest.kt` |

---

### AC-5: Local discovery privacy

| ID | Criterion | Status |
|----|-----------|--------|
| AC-5.1 | Advertisement contains ephemeral ID only — no PII | Required |
| AC-5.2 | Unknown nearby peers counted but not shown to user | Required |
| AC-5.3 | Authorized peers resolved via `AuthorizedPeerResolver` | Required |
| AC-5.4 | Ephemeral IDs rotate on interval | Required |
| AC-5.5 | Diagnostic screen demonstrates 7 anonymous / 1 authorized | Required |

**Automated tests:**

| Test | File |
|------|------|
| Unknown peers not exposed | `apps/android/feature/discovery/.../LocalDiscoveryPrivacyTest.kt` |
| Authorized resolution | `apps/android/feature/discovery/.../LocalDiscoveryPrivacyTest.kt` |
| Ephemeral ID rotation | `apps/android/feature/discovery/.../LocalDiscoveryPrivacyTest.kt` |

**Manual verification:** Launch Android app → `DiscoveryDiagnosticScreen`

---

### AC-6: Server-authoritative call authorization

| ID | Criterion | Status |
|----|-----------|--------|
| AC-6.1 | Calls require authorized relationship (contact match, connection, or Viro ID policy) | Required |
| AC-6.2 | Blocked callers receive generic unavailable response | Required |
| AC-6.3 | Self-calls rejected | Required |
| AC-6.4 | Authorization returns `callId` and `sessionMaterial` | Required |
| AC-6.5 | Client route selection happens after authorization (documented contract) | Required |

**Automated tests:**

| Test | File |
|------|------|
| Deny unknown relationship | `apps/api/src/calls/calls.service.spec.ts` |
| Authorize phone contact | `apps/api/src/calls/calls.service.spec.ts` |
| Deny blocked | `apps/api/src/calls/calls.service.spec.ts` |
| Authorize accepted connection | `apps/api/src/calls/calls.service.spec.ts` |

---

### AC-7: Call route selection

| ID | Criterion | Status |
|----|-----------|--------|
| AC-7.1 | Priority order: LAN → Wi-Fi Direct → Internet P2P → TURN | Required |
| AC-7.2 | Unavailable transports skipped | Required |
| AC-7.3 | TURN used when all direct paths unavailable | Required |
| AC-7.4 | `CallTransport` interface shared across transport modules | Required |

**Automated tests:**

| Test | File |
|------|------|
| LAN preferred over internet | `apps/android/feature/calling/.../CallRouteEngineTest.kt` |
| TURN fallback | `apps/android/feature/calling/.../CallRouteEngineTest.kt` |

---

### AC-8: Voice engine abstraction

| ID | Criterion | Status |
|----|-----------|--------|
| AC-8.1 | Feature modules depend on `VoiceEngine` interface, not Linphone directly | Required |
| AC-8.2 | `LiblinphoneVoiceEngine` stub implements full interface | Required |
| AC-8.3 | Stub simulates call state machine transitions | Required |

**Evidence:** `apps/android/voice/api/VoiceEngine.kt`, `apps/android/voice/linphone/LiblinphoneVoiceEngine.kt`

---

### AC-9: API contracts and shared types

| ID | Criterion | Status |
|----|-----------|--------|
| AC-9.1 | `packages/shared-types` builds and exports domain enums | Required |
| AC-9.2 | `packages/api-contracts` defines all v1 endpoint paths | Required |
| AC-9.3 | Android `ViroApiService` matches v1 routes | Required |
| AC-9.4 | Viro ID normalization rules enforced | Required |

**Automated tests:**

| Test | File |
|------|------|
| Viro ID normalize/format | `apps/api/src/common/utils/viro-id.util.spec.ts` |
| E.164 validation | `apps/api/src/common/utils/phone.util.spec.ts` |
| Domain model enums | `apps/android/core/model/.../DomainModelsTest.kt` |

---

### AC-10: Device identity

| ID | Criterion | Status |
|----|-----------|--------|
| AC-10.1 | Android Keystore generates EC key pair | Required |
| AC-10.2 | Private key not exportable | Required |
| AC-10.3 | Public key sent at OTP verify and device register | Required |
| AC-10.4 | Device revocation invalidates JWT | Required |

**Evidence:** `DeviceIdentityManager.kt`, `JwtStrategy.validate`

---

### AC-11: Security hygiene

| ID | Criterion | Status |
|----|-----------|--------|
| AC-11.1 | `.env` not committed; CI secret scan passes | Required |
| AC-11.2 | `allowBackup=false` on Android application | Required |
| AC-11.3 | Security events persisted to `security_events` table | Required |
| AC-11.4 | Rate limiting configured via ThrottlerModule | Required |

**Evidence:** `.github/workflows/ci.yml` secret scan step, `AndroidManifest.xml`

---

## Explicit non-goals (Phase 0)

The following are **not** acceptance criteria for Phase 0:

| Item | Phase |
|------|-------|
| Production UI / navigation | Phase 1 |
| Live Linphone audio | Phase 1 |
| Real NSD/mDNS peer discovery | Phase 1 |
| Wi-Fi Direct sessions | Phase 1 |
| FlexiSIP registration | Phase 1 |
| Admin UI | Future |
| iOS client | Future |
| Push notifications | Phase 1 |
| Call quality persistence | Phase 1 |
| Subscription billing | Future |

---

## Test coverage matrix

| Area | API tests | Android tests | Manual |
|------|-----------|---------------|--------|
| Auth hashing | ✓ | — | OTP flow |
| Contact discovery | ✓ | ✓ PhoneNormalizer | — |
| Local discovery privacy | — | ✓ | Diagnostic screen |
| Call authorization | ✓ | — | curl authorize |
| Route engine | — | ✓ | Diagnostic transport badges |
| Viro ID utils | ✓ | — | — |
| Domain models | — | ✓ | — |
| E2E voice call | — | — | Not Phase 0 |

---

## CI gates

Pull requests to `main` and `cursor/**` branches must pass:

1. API lint (`npm run lint`)
2. Migrations against test Postgres
3. API unit tests (`npm test`)
4. API build (`npm run build`)
5. Android assembleDebug, test, lint

---

## Related documents

- [ARCHITECTURE.md](ARCHITECTURE.md)
- [DISCOVERY_PRIVACY.md](DISCOVERY_PRIVACY.md)
- [SECURITY_MODEL.md](SECURITY_MODEL.md)
- [CALL_ROUTING.md](CALL_ROUTING.md)
- [DECISIONS.md](DECISIONS.md)
