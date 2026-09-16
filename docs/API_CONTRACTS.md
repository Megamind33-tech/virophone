# API Contracts — v1 (Phase 0)

## Overview

All versioned REST endpoints live under `/api/v1`. Health probes are unversioned at `/health/*`.

**Canonical contract definitions:**

- `packages/api-contracts/src/index.ts` — Endpoint paths and TypeScript interfaces
- `packages/shared-types/src/index.ts` — Shared domain types and error codes

**Base URL (development):** `http://localhost:3001`

**Authentication:** Bearer JWT in `Authorization` header unless noted.

**Validation:** NestJS `ValidationPipe` with whitelist and forbidNonWhitelisted (`apps/api/src/main.ts`).

## Error responses

Structured errors via `ViroException`:

```json
{
  "statusCode": 403,
  "message": {
    "code": "CALL_NOT_AUTHORIZED",
    "message": "You are not authorized to call this person."
  }
}
```

### Error codes

From `ApiErrorCode` in `packages/shared-types/src/index.ts`:

| Code | Typical HTTP |
|------|--------------|
| `VALIDATION_ERROR` | 400 |
| `UNAUTHORIZED` | 401 |
| `FORBIDDEN` | 403 |
| `NOT_FOUND` | 404 |
| `RATE_LIMITED` | 429 |
| `CALL_TARGET_UNAVAILABLE` | 404 |
| `CALL_NOT_AUTHORIZED` | 403 |
| `DUPLICATE_VIRO_ID` | 409 |
| `DUPLICATE_PHONE` | 409 |
| `INVALID_E164` | 400 |
| `TOKEN_EXPIRED` | 401 |
| `TOKEN_REUSE_DETECTED` | 401 |
| `DEVICE_REVOKED` | 401 |
| `ACCOUNT_SUSPENDED` | 403 |
| `INTERNAL_ERROR` | 500 |

---

## Health

### GET /health/live

Liveness probe. No authentication.

**Response 200:**

```json
{
  "status": "ok",
  "timestamp": "2026-09-14T08:00:00.000Z"
}
```

Controller: `apps/api/src/health/health.controller.ts`

---

### GET /health/ready

Readiness probe. Checks database connectivity.

**Response 200 (ready):**

```json
{
  "status": "ready",
  "database": "connected",
  "timestamp": "2026-09-14T08:00:00.000Z"
}
```

**Response 200 (not ready):**

```json
{
  "status": "not_ready",
  "database": "disconnected",
  "timestamp": "2026-09-14T08:00:00.000Z"
}
```

---

## Auth

Base path: `/api/v1/auth`

### POST /api/v1/auth/otp/request

Request OTP for phone verification. No authentication.

**Request body:**

```json
{
  "phoneE164": "+15551234567"
}
```

| Field | Type | Required | Validation |
|-------|------|----------|------------|
| `phoneE164` | string | yes | Valid E.164 after normalization |

**Response 200:**

```json
{
  "challengeId": "550e8400-e29b-41d4-a716-446655440000",
  "expiresAt": "2026-09-14T08:05:00.000Z"
}
```

**Errors:** `INVALID_E164` (400)

Phase 0 OTP delivery: logged to server console (`OTP_PROVIDER=console`).

---

### POST /api/v1/auth/otp/verify

Verify OTP and establish session. No authentication.

**Request body:**

```json
{
  "challengeId": "550e8400-e29b-41d4-a716-446655440000",
  "code": "123456",
  "devicePublicKey": "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE...",
  "platform": "ANDROID",
  "appVersion": "0.1.0"
}
```

| Field | Type | Required | Validation |
|-------|------|----------|------------|
| `challengeId` | string | yes | Valid challenge UUID |
| `code` | string | yes | Exactly 6 characters |
| `devicePublicKey` | string | yes | Non-empty Base64 public key |
| `platform` | string | yes | `ANDROID`, `IOS`, or `WEB` |
| `appVersion` | string | yes | Non-empty version string |

**Response 200:**

```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiIs...",
  "refreshToken": "uuid.uuid",
  "expiresIn": 900,
  "userId": "550e8400-e29b-41d4-a716-446655440001",
  "deviceId": "550e8400-e29b-41d4-a716-446655440002",
  "isNewUser": true
}
```

**Errors:** `VALIDATION_ERROR` (400) for invalid/expired challenge or wrong code

---

### POST /api/v1/auth/refresh

Rotate refresh token. No access token required.

**Request body:**

```json
{
  "refreshToken": "550e8400-e29b-41d4-a716-446655440000.550e8400-e29b-41d4-a716-446655440001"
}
```

**Response 200:**

```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiIs...",
  "refreshToken": "new-uuid.new-uuid",
  "expiresIn": 900
}
```

**Errors:**

| Code | Condition |
|------|-----------|
| `UNAUTHORIZED` | Unknown refresh token |
| `TOKEN_REUSE_DETECTED` | Revoked token reused (family revoked) |
| `TOKEN_EXPIRED` | Session expired |
| `DEVICE_REVOKED` | Device revoked |

---

### POST /api/v1/auth/logout

Revoke sessions for current device. **Requires JWT.**

**Request body:** none

**Response 200:**

```json
{
  "success": true
}
```

---

## Devices

Base path: `/api/v1/devices`. **All routes require JWT.**

### POST /api/v1/devices/register

Register an additional device for the authenticated user.

**Request body:**

```json
{
  "publicKey": "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE...",
  "platform": "ANDROID",
  "appVersion": "0.1.0"
}
```

**Response 200/201:** Device object

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440002",
  "userId": "550e8400-e29b-41d4-a716-446655440001",
  "publicKey": "...",
  "platform": "ANDROID",
  "appVersion": "0.1.0",
  "createdAt": "2026-09-14T08:00:00.000Z",
  "lastSeenAt": "2026-09-14T08:00:00.000Z",
  "revokedAt": null,
  "integrityStatus": "UNKNOWN"
}
```

---

### GET /api/v1/devices

List devices for authenticated user.

**Response 200:** Array of device summaries

```json
[
  {
    "id": "550e8400-e29b-41d4-a716-446655440002",
    "platform": "ANDROID",
    "appVersion": "0.1.0",
    "createdAt": "2026-09-14T08:00:00.000Z",
    "lastSeenAt": "2026-09-14T08:00:00.000Z"
  }
]
```

---

### DELETE /api/v1/devices/:id

Revoke a device. **Requires JWT.**

**Response 200:** Updated device with `revokedAt` set.

**Errors:** `NOT_FOUND` (404)

---

## Me (profile)

Base path: `/api/v1/me`. **All routes require JWT.**

### GET /api/v1/me

**Response 200:**

```json
{
  "userId": "550e8400-e29b-41d4-a716-446655440001",
  "phoneE164": "+15551234567",
  "displayName": "Alex",
  "avatarUrl": null,
  "viroId": "@alex.dev",
  "allowCallsFromViroId": "CONNECTIONS_ONLY"
}
```

---

### PATCH /api/v1/me

Partial profile update.

**Request body (all fields optional):**

```json
{
  "displayName": "Alex",
  "avatarUrl": "https://cdn.example/avatar.png",
  "viroId": "@alex.dev",
  "allowCallsFromViroId": "EXACT_ID_ALLOWED"
}
```

| Field | Type | Values |
|-------|------|--------|
| `allowCallsFromViroId` | string | `CONNECTIONS_ONLY`, `EXACT_ID_ALLOWED` |

**Response 200:** Same shape as GET /me

**Errors:**

| Code | Condition |
|------|-----------|
| `VALIDATION_ERROR` | Invalid Viro ID format |
| `DUPLICATE_VIRO_ID` | Viro ID taken |

---

## Contacts

Base path: `/api/v1/contacts`

### POST /api/v1/contacts/discover

Match hashed phone numbers against registered users. **Requires JWT.**

**Request body:**

```json
{
  "phoneHashes": [
    "a1b2c3d4e5f6...",
    "fedcba987654..."
  ]
}
```

| Field | Type | Constraints |
|-------|------|-------------|
| `phoneHashes` | string[] | Max 200 entries; HMAC-SHA256 hex |

**Response 200:**

```json
{
  "matches": [
    {
      "phoneHash": "a1b2c3d4e5f6...",
      "userId": "550e8400-e29b-41d4-a716-446655440003",
      "viroId": "@brian.m",
      "displayName": "Brian",
      "avatarUrl": null,
      "relationshipState": "PHONE_CONTACT"
    }
  ]
}
```

**Relationship states:** `PHONE_CONTACT`, `PHONE_CONTACT_AND_CONNECTION`, `VIRO_CONNECTION`, `BLOCKED`, `UNKNOWN`

**Errors:** `VALIDATION_ERROR` (400) if batch exceeds 200

---

## Directory

Base path: `/api/v1/directory`

### GET /api/v1/directory/exact/:viroId

Exact Viro ID lookup. No wildcard search. **Requires JWT.**

**Path parameter:** `viroId` — with or without `@` prefix

**Response 200:**

```json
{
  "userId": "550e8400-e29b-41d4-a716-446655440003",
  "displayName": "Brian",
  "avatarUrl": null,
  "viroId": "@brian.m"
}
```

**Errors:**

| HTTP | Condition |
|------|-----------|
| 404 | User not found, blocked, or self |
| 400 | Invalid Viro ID format |

---

## Connections

Base path: `/api/v1/connections`. **All routes require JWT.**

### POST /api/v1/connections

Create connection request.

**Request body:**

```json
{
  "targetUserId": "550e8400-e29b-41d4-a716-446655440003"
}
```

**Response 200/201:**

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440010",
  "requesterUserId": "550e8400-e29b-41d4-a716-446655440001",
  "recipientUserId": "550e8400-e29b-41d4-a716-446655440003",
  "status": "PENDING",
  "createdAt": "2026-09-14T08:00:00.000Z",
  "acceptedAt": null
}
```

**Errors:** `CALL_TARGET_UNAVAILABLE` (404) if blocked

---

### POST /api/v1/connections/:id/accept

Accept pending connection (recipient only).

**Response 200:** Connection with `status: "ACCEPTED"` and `acceptedAt` set

---

### POST /api/v1/connections/:id/reject

Reject pending connection (recipient only).

**Response 200:** Connection with `status: "REJECTED"`

---

### DELETE /api/v1/connections/:id

Revoke connection (either party).

**Response 200:** Connection with `status: "REVOKED"`

**Errors:** `NOT_FOUND`, `FORBIDDEN`

---

## Blocks

Base path: `/api/v1/blocks`. **All routes require JWT.**

### POST /api/v1/blocks

**Request body:**

```json
{
  "blockedUserId": "550e8400-e29b-41d4-a716-446655440003"
}
```

**Response 200:**

```json
{
  "success": true
}
```

---

### DELETE /api/v1/blocks/:userId

Unblock user.

**Response 200:**

```json
{
  "success": true
}
```

---

## Calls

Base path: `/api/v1/calls`. **All routes require JWT.**

### POST /api/v1/calls/authorize

Server-side call authorization. Must succeed before client route selection.

**Request body:**

```json
{
  "targetUserId": "550e8400-e29b-41d4-a716-446655440003",
  "preferredRoute": "LAN"
}
```

| Field | Type | Required |
|-------|------|----------|
| `targetUserId` | string | yes |
| `preferredRoute` | string | no — `LAN`, `WIFI_DIRECT`, `INTERNET_P2P`, `TURN_RELAY`, etc. |

**Response 200:**

```json
{
  "callId": "550e8400-e29b-41d4-a716-446655440020",
  "authorized": true,
  "expiresAt": "2026-09-14T08:05:00.000Z",
  "routeType": "INTERNET_P2P",
  "sessionMaterial": {
    "callId": "550e8400-e29b-41d4-a716-446655440020",
    "signalingUrl": "localhost"
  }
}
```

**Errors:**

| Code | HTTP | Condition |
|------|------|-----------|
| `CALL_NOT_AUTHORIZED` | 403 | No relationship |
| `CALL_TARGET_UNAVAILABLE` | 404 | Blocked |

---

### POST /api/v1/calls/:id/end

End call (caller or callee).

**Response 200:** Updated call record with `status: "ENDED"`

---

### POST /api/v1/calls/:id/events

Call telemetry webhook (Phase 0 stub).

**Response 200:**

```json
{
  "received": true
}
```

Events are not persisted in Phase 0.

---

## Endpoint registry

From `packages/api-contracts/src/index.ts`:

```typescript
export const ENDPOINTS = {
  auth: {
    otpRequest: '/api/v1/auth/otp/request',
    otpVerify: '/api/v1/auth/otp/verify',
    refresh: '/api/v1/auth/refresh',
    logout: '/api/v1/auth/logout',
  },
  devices: { register, list, delete },
  me: { get, update },
  contacts: { discover },
  directory: { exact },
  connections: { create, accept, reject, delete },
  blocks: { create, delete },
  calls: { authorize, events, end },
  health: { live, ready },
};
```

## Current product surface (beyond Phase 0)

These endpoints exist in the running API and Android Retrofit client:

| Method | Path | Auth | Notes |
|--------|------|------|-------|
| GET | `/health/metrics` | none | HTTP/WS/TURN/call counters |
| POST | `/api/v1/auth/email/otp/request` | none | Email OTP |
| POST | `/api/v1/auth/email/otp/verify` | none | Email OTP verify |
| GET | `/api/v1/me/export` | JWT | GDPR dump |
| DELETE | `/api/v1/me` | JWT | Close account |
| GET | `/api/v1/blocks` | JWT | Blocked user ids |
| GET | `/api/v1/calls/history` | JWT | Call history |
| POST | `/api/v1/messages` | JWT | Send |
| GET | `/api/v1/messages/conversations` | JWT | Inbox summaries |
| POST | `/api/v1/conferences` | JWT | Create mesh room |
| GET | `/api/v1/conferences/:id/participants` | JWT | Room members |
| GET | `/api/v1/admin/users` | admin | `X-Admin-Key` or ADMIN role |
| POST | `/api/v1/admin/users/:id/suspend` | admin | Revokes devices |
| GET | `/api/v1/admin/security-events` | admin | Recent audit rows |

Signaling WebSocket `/api/v1/signaling/ws`:

- `event: signaling` — 1:1 call frames; invite/offer/ICE fan out to every online callee device until one answers
- `event: conference` — `conf.join`, `conf.invite`, `conf.offer`, `conf.answer`, `conf.ice`, `conf.leave`

## Android client mapping

Retrofit interface: `apps/android/core/network/ViroApiService.kt`

## Related documents

- [SECURITY_MODEL.md](SECURITY_MODEL.md)
- [DISCOVERY_PRIVACY.md](DISCOVERY_PRIVACY.md)
- [CALL_ROUTING.md](CALL_ROUTING.md)
- [DATABASE_SCHEMA.md](DATABASE_SCHEMA.md)
- [COMPLETION_CHECKLIST.md](COMPLETION_CHECKLIST.md)
