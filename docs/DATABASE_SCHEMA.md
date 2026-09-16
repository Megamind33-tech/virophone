# Database Schema (Phase 0)

## Overview

Viro Reach persists data in **PostgreSQL 16**. The canonical schema is defined in:

```
apps/api/src/database/migrations/001_initial_schema.sql
```

Later additive migrations (push, messaging, email identity, admin role) live alongside it
(`002`–`006`). TypeORM entities mirror this schema under `apps/api/src/database/entities/`. Migrations are applied via:

```bash
cd apps/api && npm run migration:run
```

Runner: `apps/api/src/database/run-migrations.ts`

**Design notes:**

- UUID primary keys throughout
- `synchronize: false` in TypeORM — schema changes require explicit migrations
- Android local cache uses Room separately (`apps/android/core/database/ViroDatabase.kt`)

## Entity relationship diagram

```mermaid
erDiagram
    users ||--o{ phone_identities : has
    users ||--|| profiles : has
    users ||--o{ devices : owns
    users ||--o{ sessions : has
    users ||--o{ contact_matches : discovers
    users ||--o{ viro_connections : requests
    users ||--o{ blocks : blocks
    users ||--o{ calls : participates
    users ||--o{ subscriptions : has
    calls ||--o| call_quality : metrics
    plans ||--o{ subscriptions : defines
    devices ||--o{ sessions : binds
    users ||--o{ security_events : generates
```

## Tables

### users

Core account record. Phone number stored separately in `phone_identities`.

| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| `id` | UUID | PRIMARY KEY | User identifier (client-supplied on first OTP verify) |
| `status` | VARCHAR(20) | NOT NULL, DEFAULT `'ACTIVE'` | `ACTIVE`, `SUSPENDED`, `DELETED` |
| `admin_role` | VARCHAR(20) | NOT NULL, DEFAULT `'USER'` | `USER`, `SUPPORT`, `ADMIN`, `SECURITY_ADMIN` (`006_admin_role.sql`) |
| `created_at` | TIMESTAMPTZ | NOT NULL, DEFAULT NOW() | Account creation |
| `updated_at` | TIMESTAMPTZ | NOT NULL, DEFAULT NOW() | Last update |

Entity: `apps/api/src/database/entities/user.entity.ts`

---

### phone_identities

Verified phone numbers linked to users.

| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| `id` | UUID | PRIMARY KEY, DEFAULT gen_random_uuid() | Row ID |
| `user_id` | UUID | NOT NULL, FK → users(id) ON DELETE CASCADE | Owner |
| `phone_e164` | VARCHAR(20) | NOT NULL, UNIQUE | Verified E.164 number |
| `phone_hash` | VARCHAR(64) | NOT NULL | HMAC-SHA256 for contact matching |
| `verified_at` | TIMESTAMPTZ | NULL | Last OTP verification time |
| `status` | VARCHAR(20) | NOT NULL, DEFAULT `'PENDING'` | `PENDING`, `VERIFIED`, `REVOKED` |

**Indexes:** `idx_phone_identities_hash` on `(phone_hash)`

Entity: `phone-identity.entity.ts`

---

### profiles

Public-facing profile and Viro ID settings.

| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| `user_id` | UUID | PRIMARY KEY, FK → users(id) ON DELETE CASCADE | Owner |
| `display_name` | VARCHAR(100) | NOT NULL, DEFAULT `''` | Display name |
| `avatar_url` | VARCHAR(500) | NULL | Avatar URL |
| `viro_id` | VARCHAR(32) | NULL | Formatted ID (e.g. `@brian.m`) |
| `viro_id_normalized` | VARCHAR(32) | UNIQUE | Lowercase normalized lookup key |
| `allow_calls_from_viro_id` | VARCHAR(30) | NOT NULL, DEFAULT `'CONNECTIONS_ONLY'` | `CONNECTIONS_ONLY` or `EXACT_ID_ALLOWED` |

Entity: `profile.entity.ts`

---

### devices

Registered client devices.

| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| `id` | UUID | PRIMARY KEY, DEFAULT gen_random_uuid() | Device ID (in JWT) |
| `user_id` | UUID | NOT NULL, FK → users(id) ON DELETE CASCADE | Owner |
| `public_key` | TEXT | NOT NULL | Client public key (Base64) |
| `platform` | VARCHAR(20) | NOT NULL | `ANDROID`, `IOS`, `WEB` |
| `app_version` | VARCHAR(20) | NOT NULL | Client version |
| `created_at` | TIMESTAMPTZ | NOT NULL, DEFAULT NOW() | Registration time |
| `last_seen_at` | TIMESTAMPTZ | NOT NULL, DEFAULT NOW() | Last activity |
| `revoked_at` | TIMESTAMPTZ | NULL | Revocation timestamp |
| `integrity_status` | VARCHAR(20) | NOT NULL, DEFAULT `'UNKNOWN'` | Play Integrity status |

Entity: `device.entity.ts`

---

### sessions

Refresh token sessions with rotation family tracking.

| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| `id` | UUID | PRIMARY KEY, DEFAULT gen_random_uuid() | Session ID |
| `user_id` | UUID | NOT NULL, FK → users(id) ON DELETE CASCADE | Owner |
| `device_id` | UUID | NOT NULL, FK → devices(id) ON DELETE CASCADE | Bound device |
| `refresh_token_hash` | VARCHAR(64) | NOT NULL | HMAC hash of refresh token |
| `family_id` | UUID | NOT NULL | Rotation family for reuse detection |
| `created_at` | TIMESTAMPTZ | NOT NULL, DEFAULT NOW() | Session start |
| `expires_at` | TIMESTAMPTZ | NOT NULL | Refresh expiry |
| `revoked_at` | TIMESTAMPTZ | NULL | Revocation time |

**Indexes:** `idx_sessions_token_hash` on `(refresh_token_hash)`

Entity: `session.entity.ts`

---

### contact_matches

Discovery match metadata (not a full address book copy).

| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| `id` | UUID | PRIMARY KEY, DEFAULT gen_random_uuid() | Row ID |
| `user_id` | UUID | NOT NULL, FK → users(id) ON DELETE CASCADE | Discoverer |
| `matched_user_id` | UUID | NOT NULL, FK → users(id) ON DELETE CASCADE | Matched user |
| `phone_hash` | VARCHAR(64) | NOT NULL | Hash that produced match |
| `created_at` | TIMESTAMPTZ | NOT NULL, DEFAULT NOW() | First match time |
| `expires_at` | TIMESTAMPTZ | NOT NULL | TTL (90 days from discovery) |

**Constraints:** UNIQUE `(user_id, matched_user_id)`

Entity: `contact-match.entity.ts`

---

### viro_connections

Explicit connection requests between users.

| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| `id` | UUID | PRIMARY KEY, DEFAULT gen_random_uuid() | Connection ID |
| `requester_user_id` | UUID | NOT NULL, FK → users(id) ON DELETE CASCADE | Initiator |
| `recipient_user_id` | UUID | NOT NULL, FK → users(id) ON DELETE CASCADE | Recipient |
| `status` | VARCHAR(20) | NOT NULL, DEFAULT `'PENDING'` | `PENDING`, `ACCEPTED`, `REJECTED`, `REVOKED` |
| `created_at` | TIMESTAMPTZ | NOT NULL, DEFAULT NOW() | Request time |
| `accepted_at` | TIMESTAMPTZ | NULL | Acceptance time |

**Constraints:** UNIQUE `(requester_user_id, recipient_user_id)`

Entity: `viro-connection.entity.ts`

---

### blocks

User blocking relationships.

| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| `blocker_user_id` | UUID | NOT NULL, FK → users(id) ON DELETE CASCADE | Blocker |
| `blocked_user_id` | UUID | NOT NULL, FK → users(id) ON DELETE CASCADE | Blocked user |
| `created_at` | TIMESTAMPTZ | NOT NULL, DEFAULT NOW() | Block time |

**Constraints:** PRIMARY KEY `(blocker_user_id, blocked_user_id)`

Entity: `block.entity.ts`

---

### calls

Call session records (authorization and lifecycle).

| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| `id` | UUID | PRIMARY KEY, DEFAULT gen_random_uuid() | Call ID |
| `caller_user_id` | UUID | NOT NULL, FK → users(id) | Caller |
| `callee_user_id` | UUID | NOT NULL, FK → users(id) | Callee |
| `started_at` | TIMESTAMPTZ | NOT NULL, DEFAULT NOW() | Initiation time |
| `answered_at` | TIMESTAMPTZ | NULL | Answer time |
| `ended_at` | TIMESTAMPTZ | NULL | End time |
| `status` | VARCHAR(20) | NOT NULL, DEFAULT `'INITIATED'` | Call status enum |
| `route_type` | VARCHAR(20) | NULL | Selected route |

Entity: `call.entity.ts`

---

### call_quality

Post-call quality metrics (one row per call).

| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| `call_id` | UUID | PRIMARY KEY, FK → calls(id) ON DELETE CASCADE | Call reference |
| `latency` | FLOAT | NULL | Latency (ms) |
| `jitter` | FLOAT | NULL | Jitter (ms) |
| `packet_loss` | FLOAT | NULL | Packet loss ratio |
| `bitrate` | FLOAT | NULL | Bitrate |
| `codec` | VARCHAR(20) | NULL | Codec name |
| `route` | VARCHAR(20) | NULL | Route used |
| `relayed` | BOOLEAN | NOT NULL, DEFAULT FALSE | TURN relay used |

Entity: `call-quality.entity.ts`

---

### plans

Subscription plan catalog (scaffold for Phase 0).

| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| `id` | UUID | PRIMARY KEY, DEFAULT gen_random_uuid() | Plan ID |
| `name` | VARCHAR(100) | NOT NULL | Plan name |
| `description` | TEXT | NULL | Description |
| `is_active` | BOOLEAN | NOT NULL, DEFAULT TRUE | Active flag |
| `created_at` | TIMESTAMPTZ | NOT NULL, DEFAULT NOW() | Creation time |

Entity: `plan.entity.ts`

---

### subscriptions

User subscription records (scaffold).

| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| `id` | UUID | PRIMARY KEY, DEFAULT gen_random_uuid() | Subscription ID |
| `user_id` | UUID | NOT NULL, FK → users(id) ON DELETE CASCADE | Subscriber |
| `plan_id` | UUID | NOT NULL, FK → plans(id) | Plan reference |
| `status` | VARCHAR(20) | NOT NULL, DEFAULT `'ACTIVE'` | Subscription status |
| `created_at` | TIMESTAMPTZ | NOT NULL, DEFAULT NOW() | Start time |
| `expires_at` | TIMESTAMPTZ | NULL | Expiry |

Entity: `subscription.entity.ts`

---

### security_events

Audit log for security-relevant events.

| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| `id` | UUID | PRIMARY KEY, DEFAULT gen_random_uuid() | Event ID |
| `user_id` | UUID | NULL, FK → users(id) | Associated user |
| `device_id` | UUID | NULL | Associated device |
| `event_type` | VARCHAR(50) | NOT NULL | Event type enum |
| `severity` | VARCHAR(20) | NOT NULL | `LOW`, `MEDIUM`, `HIGH`, `CRITICAL` |
| `metadata` | JSONB | NULL | Structured context |
| `created_at` | TIMESTAMPTZ | NOT NULL, DEFAULT NOW() | Event time |

**Indexes:**

- `idx_security_events_user` on `(user_id)`
- `idx_security_events_type` on `(event_type)`

Entity: `security-event.entity.ts`

---

### otp_challenges

Temporary OTP verification state.

| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| `id` | UUID | PRIMARY KEY, DEFAULT gen_random_uuid() | Challenge ID |
| `phone_e164` | VARCHAR(20) | NOT NULL | Target phone |
| `code_hash` | VARCHAR(64) | NOT NULL | HMAC hash of OTP |
| `attempts` | INT | NOT NULL, DEFAULT 0 | Failed attempt count |
| `created_at` | TIMESTAMPTZ | NOT NULL, DEFAULT NOW() | Creation time |
| `expires_at` | TIMESTAMPTZ | NOT NULL | Expiry |
| `verified_at` | TIMESTAMPTZ | NULL | Successful verification time |

Entity: `otp-challenge.entity.ts`

---

### schema_migrations

Migration tracking table.

| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| `version` | VARCHAR(50) | PRIMARY KEY | Migration identifier |
| `applied_at` | TIMESTAMPTZ | NOT NULL, DEFAULT NOW() | Apply timestamp |

---

## Android local schema (Room)

Separate from PostgreSQL; caches known contacts on device.

**Database:** `apps/android/core/database/ViroDatabase.kt`

### known_contacts

| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| `userId` | String | PRIMARY KEY | Remote user ID |
| `localName` | String | NOT NULL | Device address book name |
| `phoneE164` | String | NULL | Normalized phone |
| `viroId` | String | NULL | Viro ID |
| `relationshipState` | String | NOT NULL | Enum string |
| `presence` | String | DEFAULT `'OFFLINE'` | Presence enum string |

Room version: 1, `exportSchema = true`

## Environment

| Variable | Default | Purpose |
|----------|---------|---------|
| `DATABASE_URL` | `postgresql://viro:viro_dev_password@localhost:5432/viro_reach` | Connection string |
| `POSTGRES_USER` | `viro` | Docker Compose user |
| `POSTGRES_PASSWORD` | (see `.env.example`) | Docker Compose password |
| `POSTGRES_DB` | `viro_reach` | Database name |

Init script: `infra/postgres/init.sql` (extensions and privileges)

## Related documents

- [SECURITY_MODEL.md](SECURITY_MODEL.md)
- [DISCOVERY_PRIVACY.md](DISCOVERY_PRIVACY.md)
- [API_CONTRACTS.md](API_CONTRACTS.md)
