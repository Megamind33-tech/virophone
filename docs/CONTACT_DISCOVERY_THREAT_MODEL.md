# Contact Discovery Threat Model (Phase 0.5)

## What this is NOT

- **Not** cryptographic private-set intersection (PSI)
- **Not** privacy-preserving from the server (server sees submitted E.164 numbers)
- **Not** protected by client-side secrets (APK secrets are recoverable)

## What this IS

**Authenticated, rate-limited, batch-capped contact matching** with server-side keyed hashing for storage lookup only.

## Mechanism (post Phase 0.5)

1. Client normalizes phone numbers to E.164 locally (`libphonenumber`).
2. Client sends `phonesE164[]` over **authenticated TLS** to `POST /api/v1/contacts/discover`.
3. Server re-normalizes, hashes with **server-only** `CONTACT_HASH_SALT` for `phone_identities` lookup.
4. Server returns matches only for numbers the user submitted that correspond to registered users.
5. Blocked relationships excluded; no global phone directory endpoint.

## HMAC key location

| Secret | Location | Client access |
|--------|----------|---------------|
| `CONTACT_HASH_SALT` | Server env only | **None** (removed from Android APK in Phase 0.5) |
| `JWT_*` secrets | Server env only | None |
| Refresh tokens | Client memory | User session only |

## Threat analysis

### Can client compromise enable enumeration?

**Before Phase 0.5:** Yes — shared `CONTACT_HASH_SALT` in APK allowed offline precomputation of hashes for plausible number spaces.

**After Phase 0.5:** Client compromise enables an authenticated user to submit arbitrary E.164 batches (subject to rate limits). This is equivalent to a malicious authenticated user, not offline global enumeration.

### Stable identifiers?

Identical phone numbers produce identical server-side `phone_hash` (deterministic HMAC). This is intentional for database lookup, not client-facing.

### Zambia number space precomputation?

An attacker with the **server salt** could precompute hashes offline. Mitigation: salt never in client; protect server secrets.

An attacker **without** the salt cannot precompute matching hashes from the API alone — they must submit candidate E.164 values through the authenticated, rate-limited endpoint.

### Oracle abuse?

`POST /contacts/discover` could be abused by authenticated users probing number batches. Mitigations:

- Authentication required
- Max 200 per batch
- Rate limiting (`@nestjs/throttler`)
- Suspicious batch size auditing (`SUSPICIOUS_ENUMERATION` security event)
- No per-number existence leak for numbers not in batch

Directory exact Viro ID lookup is separate; partial/wildcard queries rejected.

## Future path

OPRF/PSI may be evaluated in a later phase if product requires server-blind matching. Not implemented in Phase 0.5.
