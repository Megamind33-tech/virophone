# PHASE 1 — VIRO NOW REPORT

Date: 2026-09-21. Worktree: `C:\viro phone\viro-reach`, branch `main`.

Implementation is limited to the supplied Phase 1 specification. Release evidence is recorded below; physical acceptance must be supplied by the two testers and is not inferred from compilation or API tests.

## 1. Files changed

Android, relative to `apps/android`:

- `app/src/main/kotlin/com/viroreach/app/moments/MomentsRepository.kt`
- `app/src/main/kotlin/com/viroreach/app/moments/MomentsUi.kt`
- `app/src/main/kotlin/com/viroreach/app/consumer/ConsumerNav.kt`
- `app/src/main/kotlin/com/viroreach/app/consumer/messages/MessagesInboxScreen.kt`
- `app/src/main/kotlin/com/viroreach/app/session/SessionManager.kt`
- `app/src/test/kotlin/com/viroreach/app/moments/MomentsRepositoryTest.kt`
- `core/network/src/main/kotlin/com/viroreach/core/network/ViroMomentsApi.kt`
- `core/network/src/main/kotlin/com/viroreach/core/network/ViroApiClient.kt`
- `feature/calling/src/main/kotlin/com/viroreach/feature/calling/CallManager.kt`
- `feature/calling/src/test/kotlin/com/viroreach/feature/calling/CallTargetResolverTest.kt`

Backend, relative to `apps/api`:

- `src/database/migrations/025_moments.sql`
- `src/moments/moments.controller.ts`
- `src/moments/moments.service.ts`
- `src/moments/moments.module.ts`
- `src/app.module.ts`
- `src/blocks/blocks.service.ts`
- `src/connections/connections.service.ts`
- `test/integration/moments.integration.spec.ts`
- Existing integration fixtures: `test-app.ts`, `messaging.integration.spec.ts`, `email-auth.integration.spec.ts`, `phase05.integration.spec.ts`, `phase06.integration.spec.ts`, `conference.integration.spec.ts`, `signaling.integration.spec.ts`, `redis.integration.spec.ts`.

Supporting documents: this report, `AGENT_WORK_AUDIT_2026-09-21.md`, and `VIRO_NOW_TESTER_CHECKLIST.md`.

## 2. Database changes

Migration 025 adds `moments`: creator, type, optional text, visibility, creation/expiry timestamps and ACTIVE/ENDED/EXPIRED state. A partial unique index enforces one active Moment per creator, including concurrent requests. An expiry index supports the server worker. No participant or full-room tables are added in Phase 1.

The existing migration runner discovers the SQL file and applies it transactionally. Reads reject expired records immediately, including before the five-second expiry worker runs. Extension is capped at two hours from creation.

## 3. API endpoints

All routes require the existing JWT authentication:

| Method | Route | Purpose |
|---|---|---|
| POST | `/api/v1/moments` | Create one active Moment |
| GET | `/api/v1/moments/now` | Server-filtered active list and server time |
| GET | `/api/v1/moments/:id` | Authorized active detail |
| POST | `/api/v1/moments/:id/extend` | Owner-only extension |
| DELETE | `/api/v1/moments/:id` | Owner-only end |

## 4. Realtime events

`moment.created`, `moment.updated`, `moment.ended`, `moment.expired` use the existing authenticated signaling connection and Redis-backed delivery registry. Payloads are empty invalidations; clients fetch an authorized list rather than receiving potentially stale activity text in queued events. Blocks and connection changes also invalidate the list without disclosing whether a Moment exists.

## 5. Android components

Home reuses the existing inbox beneath My Moment and Now; existing bottom navigation remains. The dialer shortcut is available on Calls. Now initially shows at most four compact cards; See all opens the full list. The creation sheet offers seven types, adjustable duration and connections/host-owned contacts. Detail shows activity, remaining time and audience. The owner can extend or end; Free uses the existing audio-call flow after rechecking access.

The repository caches within the session, refreshes on realtime/reconnection, prunes expired entries and clears on logout/account changes. Successful mutations immediately update the cache even if the follow-up refresh fails. Material controls in the new UI explicitly use legible colors on Viro's navy surfaces in either system theme mode.

## 6. Tests added

Moments integration coverage includes authentication, persisted creation, allowed/denied audiences, both directions of blocking, invalid input, seven types, concurrent creation, owner authorization, extension cap, ending, expiry, restart, host-owned contacts, connection states, avatar privacy and WebSocket lifecycle events.

Nine Android repository/helper tests cover expiry, offline cache, account changes, authoritative removal, successful mutations followed by failed refresh, stale-account mutation responses, countdown rounding and activity labels. The existing calling-test mock now tolerates the expanded API interface.

The previously excluded/early-returning API regression suites were exercised against real isolated PostgreSQL and Redis. Their fixtures now use current migrations for messaging/email auth, explicitly accommodate many OTP fixture registrations from one runner IP in the affected suites, and establish a contact before testing contact-only presence. Missing dependencies fail the repaired suites instead of reporting an unexecuted test as passed. Production OTP limits are unchanged.

## 7. Test results

- Android `:app:assembleDebug testDebugUnitTest --offline`: passed; 113 tests, zero failures/errors. Rerun on the release commit `4b7dc7d` (all Android modules): 113/113 again.
- Compose regression checker: 933 methods, zero imbalanced.
- Backend isolated full suite (`jest --runInBand`, builder image + isolated PostgreSQL 16 and Redis 7 on the VPS): **42/42 suites, 308/308 tests passed**, 203 s, final run 2026-09-21 09:58 UTC. Log preserved on the VPS at `/tmp/viro-moments-validation-20260921/tests-final.log`.
- `git diff --check`: passed.

## 8. Two-device test result

Pending tester execution. The user confirmed two Android phones/accounts are available. No physical phones are attached to this machine. Follow `VIRO_NOW_TESTER_CHECKLIST.md`; do not mark device acceptance complete until results arrive.

## 9. Privacy/block test result

Server tests cover list and direct-detail denial, generic unavailable responses, both block directions, existing blocks, strangers, expired contacts and inactive connections. Final isolated-run counts are recorded with the release evidence. Device-side block removal remains on the tester checklist.

## 10. Known limitations

- Full rooms, participant management, temporary chat, reactions and Join/Knock are deliberately deferred by the Phase 1 instruction.
- Audiences are accepted Viro connections and the host's synced contacts; no public discovery, close-connections list or selected group is invented.
- Cached information already received before going offline cannot be remotely erased until reconciliation; access to detail/calling is checked against the server.
- Two-phone behavior, actual audio and narrow-screen visual acceptance require tester results.

## 11. Screenshots

Pending tester captures. The previously attempted emulator failed with WHPX. No mockup is presented as a screenshot of the running app.

## 12. Commit and delivery

Released 2026-09-21, following the existing `scripts/vps-deploy.sh` and `scripts/distribute-android.sh` workflow.

**Implementation commit:** `4b7dc7d` — "Now, phase one: what you're up to, for the people allowed to see it" (29 files, +1126/−210, including this report, the audit and the tester checklist).

**Backend deployment (https://reach.viro3.online):**

- Pre-release safety, before deploying: running API image tagged `viro-reach-api:pre-moments-20260921` (rollback point) and database backed up to `/opt/viro-reach/backups/pre_moments_20260921T101100Z.sql.gz` (gzip-verified).
- `scripts/vps-deploy.sh` completed 2026-09-21 10:14 UTC; `/health/live` and `/health/ready` OK (database and redis connected, media configured); LiveKit verified listening by the deploy script.
- Migration `025_moments` recorded in `schema_migrations` at 10:13:59 UTC; `moments` table present with all three indexes, including the partial unique `moments_one_active_per_creator`.
- Production smoke test (hardware-test OTP flow via the public URL): authenticated create → now list → extend (expiry correctly advanced to creation + 30 min) → end → now list empty. All 2xx. Unauthenticated `GET /api/v1/moments/now` returns 401 both directly and through Caddy.

**Tester APK (Firebase App Distribution, debug build):**

- versionCode 91, versionName `0.4.91-4b7dc7d`, built from commit `4b7dc7d`.
- SHA-256 `faf5d366844304611a9c6d89ccdc43a1c75ec50683d886a6c9c84548747f4820`; Firebase's uploaded-binary URL names the same digest.
- Release `0jf4oj33crpig` distributed to the configured tester with the checklist notes attached.
- Console: https://console.firebase.google.com/project/viro-8a/appdistribution/app/android:com.viroreach.app/releases/0jf4oj33crpig
- Tester link: https://appdistribution.firebase.google.com/testerapps/1:74644641402:android:57579c43fba4ecbde98dc3/releases/0jf4oj33crpig

This is a tester debug build delivered through the existing distribution script, not a Play production release. Device acceptance (section 8) remains open until the two testers return results against this release.
