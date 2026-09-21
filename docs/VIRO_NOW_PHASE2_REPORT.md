# VIRO NOW — PHASE 2 REPORT

Date: 2026-09-21. Worktree: `C:\viro phone\viro-reach`, branch `main`, commit `e8a301a`.

Phase 1 was not rebuilt; it was extended where its specification deferred scope (rooms, participants, room chat, reactions, Knock, invitations). Physical two-device acceptance remains with the testers and is not inferred from compilation or API tests.

## 1. Files changed

Backend, relative to `apps/api`:

- `src/database/migrations/026_moment_rooms.sql` — participants, messages, reactions, knocks, invitations.
- `src/moments/moments.service.ts` — room lifecycle and every Phase 2 authorization rule.
- `src/moments/moments.controller.ts` — join/leave/room/messages/react/knock(s)/respond/invites/invitations routes.
- `src/moments/moments.module.ts` — imports PushModule for offline knock/invite pushes.
- `test/integration/moments-rooms.integration.spec.ts` — new Phase 2 suite (9 cases).
- `test/integration/moments.integration.spec.ts` — ended/expired frames now carry `{momentId}` only.
- `test/integration/account-admin.integration.spec.ts`, `test/integration/redis.integration.spec.ts` — 120 s hook timeouts (schema growth turned the 5 s default into a false dependency failure).

Android, relative to `apps/android`:

- `core/network/.../ViroMomentsApi.kt` — room, message, reaction, knock, invitation models and routes.
- `app/.../moments/MomentsRepository.kt` — invitations, knock/invite/respond actions, socket-frame fan-out, `MomentRoomState`.
- `app/.../moments/MomentsUi.kt` — NowScreen (Your Moment / Active now / Invitations), Moment Room (Chat|People), knock dialog, invite sheet.
- `app/.../consumer/ConsumerNav.kt` — Now destination, knock listener, chat opening from a room.
- `app/.../consumer/messages/MessagesInboxScreen.kt` — moments header removed; conversations only.
- `core/designsystem/.../ViroComponents.kt`, `ViroConsumerUi.kt` — `ViroConsumerTab` is Now/Chats/Calls/Contacts/You; Home and Messages are gone.
- `app/.../session/SessionManager.kt` — forwards moment frames to the repository; `openMomentRoom`.
- `app/build.gradle.kts` — retrofit as a test dependency (fakes simulate HTTP statuses).
- `app/src/test/.../MomentsRepositoryTest.kt` — 9 new room/repository tests.

## 2. Existing systems reused

Realtime: the existing signaling WebSocket and `RealtimeRegistry` deliver every `moment.*` frame; the app collects `callManager.messagingFrames` exactly as before. Push: `PushService` sends offline knock/invite notifications. Voice: Knock-Accept and room Talk call `SessionManager.placeOutgoingCall` → LiveKit call flow, and appear in the normal Calls history. Chat: room messages reuse the message transport pattern (realtime frame with hydrated DTO) but a separate table and surface. Connections: `PeopleRepository.refreshConnections` powers the invite sheet. UI: Viro design system (surfaces, avatars, chips, sheets) and the existing profile/chat destinations. Navigation: existing overlay-based routing (`ChatRoute`, `ConsumerOverlay`).

## 3. Models extended

The Phase 1 `moments` table is unchanged apart from reads. Migration 026 adds `moment_participants` (PK moment+user; the host row is inserted at creation), `moment_messages`, `moment_reactions` (one live reaction per user per message), `moment_knocks` (PENDING/ACCEPTED/DISMISSED, unique per moment+knocker), and `moment_invitations` (unique per moment+invitee). No equivalent models were created.

## 4. API changes

All under `/api/v1/moments`, all JWT-guarded, all authorizing server-side: `POST :id/join` (idempotent; returns the room), `POST :id/leave` (guests; the host must end instead), `GET :id/room` (participants only), `POST :id/messages`, `POST :id/messages/:mid/react` (emoji or null), `POST :id/knock` (Free Moments, non-host, visible audience), `GET :id/knocks` + `POST :id/knocks/:knockerId/respond` (host only), `POST :id/invites` (host, accepted connections, block-checked), `GET invitations` + `DELETE invitations/:id` (invitee; list re-checks visibility, so later blocks or a dead Moment remove entries without action). Action routes return 200; message and Moment creation keep 201.

## 5. Realtime events

`moment.joined`, `moment.left` (to current participants), `moment.message` (inline hydrated DTO, deduped by id client-side), `moment.reaction` (inline, then authoritative refetch), `moment.knock` (host only; push when offline), `moment.invited` (invitee; push when offline), and Phase 1's `moment.ended`/`moment.expired` now carry `{momentId}` so open rooms close precisely. `moment.created`/`moment.updated` remain empty invalidations.

## 6. Moment Room implementation

Header: creator avatar, name, activity, remaining time, participant count. Chat | People tabs. Chat: bubbles, long-press reaction picker (❤️😂🔥👏👍, remove mine), composer capped at 500 characters, live scroll. People: participants with Host tag, "Message" opens the existing chat, host-only "Invite connections", guest-only "Talk" on Free Moments (re-verifies access, then the existing call flow). Entering joins (idempotent; the host is a participant from creation); backing out leaves for guests; `closed` shows "This Moment has ended." and returns to Now. Frames apply inline for chat/reactions and trigger authoritative refetches for membership — reconnects cannot duplicate participants or messages.

## 7. Knock implementation

One primary action per card: Knock on Free, Join on everything else. Knock validates Free + non-host + audience, upserts a PENDING row, and notifies the host in-app (dialog anywhere in the app via `MomentKnockListener`) or by push. Accept marks the knock and launches the ordinary call flow; Not now dismisses. No separate voice stack, call history, or signaling exists for knocks.

## 8. Automated tests

- Backend isolated full suite (builder image + isolated PostgreSQL 16 + Redis 7 on the VPS): **43/43 suites, 319/319 tests passed**, 250 s, final run 2026-09-21 12:12 UTC; log preserved at `/tmp/viro-phase2-validation/tests.log`.
- Phase 2 suite covers: authentication on every route; the §33 loop (join → both show 2 → message → reaction → leave → count 1); join idempotency; strangers/blocked users denied by direct API; participant-only chat/reactions, reaction switch/remove, cross-room reaction denied; knock rules (Free-only, non-host, audience, host-only list/respond, single announcement); push fallback when offline; invitation rules (host/connections/blocks/decline/visibility-based removal); end and expiry purge all five room tables while the Moment keeps its terminal state; WebSocket delivery of invited/joined/message/reaction/left/ended; and that no `conversations`/`messages` rows are ever created.
- Android: `:app:testDebugUnitTest` and all modules — **122/122 pass** on the release commit; `assembleDebug` builds.
- `git diff --check`: passed.

## 9. Two-device test

Pending testers, per the updated `VIRO_NOW_PHASE2_TESTER_CHECKLIST.md` and the Firebase release notes (§33 and §34 flows included). Not marked complete until results arrive.

## 10. Call integration test

Automated: knock → host-only notification → respond → the existing `placeOutgoingCall`/LiveKit path, unchanged from normal calling; calls land in the existing Calls history (no Moment-specific call records exist to create duplicates). Live audio between two physical phones remains a tester step (§34 in the checklist).

## 11. Block/privacy test

Automated: a blocked user cannot see, join, knock, receive invitations for, or read the room of the host's Moment; participants cannot be inspected via direct API by non-participants; rooms purge on end/expiry so nothing remains to inspect. Device-side block removal is on the tester checklist.

## 12. Known limitations

- Moment room chat is plaintext server-side; it is not end-to-end encrypted (Phase 1 chat encryption covers 1-to-1 conversations). Rooms are temporary and purged at close.
- Reactions exist only on room messages; there is no reaction to the Moment itself, no counts, no ranking.
- Audience editing after creation remains out of scope, as in Phase 1.
- Narrow-screen/dark-mode/large-text visual acceptance requires tester captures.

## 13. Screenshots

Pending tester captures. The previously attempted emulator failed with WHPX; no mockup is presented as a screenshot.

## 14. Commit and delivery

Released 2026-09-21 following `scripts/vps-deploy.sh` and `scripts/distribute-android.sh`.

- Implementation commit: `e8a301a` — "Now, phase two: the room around a Moment, and Knock" (18 files, +1543/−182).
- Pre-deploy safety: image tagged `viro-reach-api:pre-phase2-20260921`; database backed up to `/opt/viro-reach/backups/pre_phase2_20260921T122324Z.sql.gz` (gzip-verified); health ready before and after.
- Migration `026_moment_rooms` recorded at 12:26:16 UTC; all six moment tables present.
- Production smoke test via the public URL (two real test accounts, connection established first): knock → host sees pending knock → accept → join (2 participants, host flagged) → message → 🔥 reaction → invite → invitation listed → leave (count 1) → end. After end: join/room 404, invitations empty, all five room tables 0 rows, Moment status ENDED.
- APK: versionCode 93, versionName `0.4.93-e8a301a`, SHA-256 `18201581ac5b341e2507c652a26cd853fa066d3fe90174638320441457f82793`.
- Firebase App Distribution release `2ev6i0aji18i8`, notes and checklist attached, distributed to the configured tester. Firebase's uploaded-binary URL names the same digest.
  Console: https://console.firebase.google.com/project/viro-8a/appdistribution/app/android:com.viroreach.app/releases/2ev6i0aji18i8
  Tester link: https://appdistribution.firebase.google.com/testerapps/1:74644641402:android:57579c43fba4ecbde98dc3/releases/2ev6i0aji18i8

**No duplicate Chat, Call, Contact, or Moment systems were introduced** — verified against the failure conditions in §35: room messages never create `conversations`/`messages` rows (asserted in tests), Now contains no conversation list, Chats contains no Moment feed, Calls keeps the only call history, ended Moments are unjoinable, blocked users are denied by direct API, and reconnects can duplicate neither participants nor messages.
