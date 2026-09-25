# Claude continuation — 2026-09-24

Continued the only registered checkout, `C:\viro phone\viro-reach`, on `main`.
Read Claude's project memory and interrupted session. Preserved and completed
its uncommitted correction to Knock wording: a host's mood must never be
attributed to the visitor.

## NOW and Moments

- Extended the active FeaturedMoment renderer with activity, company and written
  Moment compositions, creator/age, actual invitation context and expiry.
- Horizontal paging uses existing Moment IDs, prioritizes direct invitations,
  and opens the existing room. One primary Join action plus Knock and the
  existing private reaction action; no public reaction counts were added.
- Studying and Working creation choices use existing WORKING + STAY values.
  Audience enforcement, explicit duration and the existing mood flow remain.
- Cache/expiry behavior stays in MomentsRepository. No parallel store or screen.
- Empty/loading copy distinguishes a quiet feed from a first fetch.

## Knock and notifications

- Existing Knock API now supports all eligible active Moments, including Study.
  Context names the host's actual activity without attributing their mood or
  invitation words to the visitor.
- Talk now is explicit; Message and Not now reuse existing response and navigation
  paths. Failed responses remain visible and do not start a call.
- Response events update both users; host badges refresh. Persisted pending
  Knocks can be recovered after restart instead of relying on a missed socket.
- Foreground Moment pushes show a notification; notification taps route to the
  inbox/Now. Invitations remain in the existing inbox invitation collection.

## Message retention

The server's ordinary messages have no expiry. A confirmed client history gap
made messages look missing: initial sync includes only 60 messages per chat,
but the existing older-history loader had no UI caller.

- Added Load older messages, preserving the current position when prepending.
  Failed history requests remain retryable instead of marking history exhausted.
- Missing sync collections are rejected instead of being interpreted as an empty
  inbox that authorizes local deletion. Account identity is checked after requests.
- Local placeholder conversations and queued sends are preserved during pruning.
- Outbox replacement no longer deletes the original before decoding/upserting
  the authoritative message; the existing unique clientMsgId handles replacement.
- Integration regression verifies that a normal message sent with disappearing
  disabled has no expiry, survives cleanup, and remains accessible in old history.
  Existing disappearing/private-session expiry tests still pass.

These changes do not recover already deleted content or decrypt old ciphertext
for a device that never had its keys.

## Hidden bugs and theme audit

- NOW and Messages both recreated MomentRoomState during recomposition. Both now
  remember one state per open room, preventing routine refreshes from repeatedly
  rebuilding native media sessions.
- The existing response UI swallowed Knock failures; fixed as described above.
- The existing cleanup regression test called nonexistent POST /end. It now
  exercises the actual DELETE route used by the app.
- NOW controls use semantic theme colors; written cards use themed surfaces and
  text, artwork keeps a dark scrim, and compact cards grow with content.
- Light-mode muted text and accent have stronger contrast. Dark chat bubbles and
  room artwork intentionally retain light text on their own dark backgrounds.
- Claude's existing room-cleanup retry and Rive animation-only safety fixes remain.

## Verification

- API unit tests: 182 passed across 28 suites.
- API TypeScript check: passed.
- Messaging/relationships integration: 19 passed, including retention regression.
- Moments/rooms integration: 15 passed, including Study → NOW → contextual Knock
  → response fanout → room join → actual end, audience/block checks, and cleanup.
- Android APK build and all-module JVM tests: passed; 176 tests, zero failures.
- Claude's Compose bytecode guard: 1,175 methods, zero imbalances.
- Existing VPS deployment script completed; database/Redis/media readiness passed.
- Used Claude's PGlite scratch database and REDIS_MOCK=1; no production test users
  or fabricated activity were created.
- Logs: tmp/continuation-android-final.log, tmp/continuation-integration.log,
  tmp/continuation-moments.log, tmp/continuation-typecheck.log.

No Android device is attached. Two-phone visual/gesture checks, background FCM,
live audio and repeated native Rive opening require real hardware. The API flow
above is automated integration evidence, not a claim of a two-phone test.
