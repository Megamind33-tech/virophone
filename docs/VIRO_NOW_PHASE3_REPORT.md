# Viro Now — Phase 3

Closes the three things Phase 2 listed as known limitations, and fixes the
reason end-to-end encryption had never actually worked on a real phone.

## 1. Encryption was not working, and why

The report said one-to-one chats were encrypted end to end, and the switch was
on. Production said otherwise:

```
identity_keys=3   prekeys=400   envelopes=0
devices=57        users=16
```

Three devices out of fifty-seven had ever published keys, and not one message
had ever been sealed. Encryption *at rest* was working — 30 of 37 message
bodies carried the `v1.` prefix — which is what made it look fine from the
database. End-to-end was not.

Two separate bugs, both on the phone:

**Registration ran before there was an account.** `SessionManager` starts
messaging in its constructor, once per process, and `MessagingRepository.start()`
returned early on any later call. For anyone signing *in* — as opposed to being
restored into an existing session — that first call happened before there was a
user id to register keys for, so `setUpEncryption()` returned immediately and
nothing ever called it again until the app was killed and reopened. The three
devices that did register were the ones that happened to start already signed
in. `start()` now redoes its session-scoped work on every call (first sync,
feature flags, key registration) and starts its collectors once; `SessionManager`
calls it again from `onAuthenticationSuccess`.

**A failed key upload was never retried.** `E2eeEngine.register()` generated
keys, saved them locally, and *then* published the public half. If that upload
failed — no signal on the bus, a 500, anything — the exception was caught and
logged, but the local identity had already been written. Every later launch saw
an identity that matched and returned early, so the device looked registered to
itself while being absent from the directory: nobody could fetch a bundle for
it, nobody could seal to it, and every chat it was in silently stayed in the
clear, permanently. `own_identity` now carries `publishedAt`, set only after
the directory accepts the keys; a launch that finds 0 re-publishes the keys the
device already holds. Not new keys — a new identity key would raise a
safety-number warning on every contact's phone, which is a warning about an
attack and must not be spent on our own retry. `isRegistered()` now means "the
directory knows this device", because that is what sealing actually depends on.

Every existing install gets `publishedAt = 0` from the migration, which is the
honest answer: the database cannot know whether its original upload landed. So
every phone re-publishes once on its next launch, and the 54 devices that were
never in the directory repair themselves without anyone reinstalling.

*Both fixes ship in the app, not on the server.* Nothing changes until phones
take the build.

## 2. Moment room chat, end to end

`moment_messages.body` was a plain column: the one place in Viro where several
people talk at once was the one place the server could read.

A room message is now sealed once per device, into `moment_message_envelopes`,
using the same sessions ordinary chats use — so saying something in a Moment and
sending a private message later ride the same session and the safety number
means one thing. A room deliberately has no group key of its own: it lives for
at most two hours, membership changes while people are talking, and the whole
thing is deleted when the Moment ends, so a ratcheting group key would need
rekeying on every join and would outlive what it protects.

- `moment_messages.body` is nullable. A message has either a readable body or
  envelopes and no body, never both — a request carrying both is stored sealed
  and the body dropped.
- `moment_messages.sender_device_id` records which device sealed it, because a
  copy can only be opened against a session with that device.
- Envelopes are only accepted for devices currently in the room. A copy
  addressed to a device that left is refused, so the server cannot be made to
  hold one.
- A sealed message goes out as one frame per device, each carrying only that
  device's own copy. A plaintext one still goes to the room.
- The room read joins on `device_id = <the device asking>`: it can only ever
  hand over one device's own envelope.
- The room falls back to the clear when anyone in it has no keys, on the same
  rule groups follow: half a room is not a room. The lock in the header appears
  only once something has actually been sealed there.

Two things this does not do, by design rather than omission:

- **No backlog for a late arrival.** A message sealed before a device was in the
  room was never addressed to it and cannot be afterwards. Those messages are
  left out of that device's view entirely rather than shown as an unreadable
  placeholder. (A message whose copy exists but will not open — already read on
  another device — does show a placeholder, because something was said and
  pretending otherwise would be a stranger kind of wrong.)
- **Reactions on room messages stay readable.** `moment_reactions` is still
  `(message, user, emoji)` aggregated by the server. Sealing those is the same
  work again and is recorded in `docs/encryption-design.md` §12 rather than
  half-done here.

## 3. Reactions to the Moment itself

Phase 2 had reactions only on messages inside a room, which meant the only way
to respond to someone saying they were free was to join their room — a much
bigger step than "I saw that".

`moment_cheers` holds one live reaction per person per Moment: reacting again
switches the emoji, `null` takes it back. The Now feed carries `reactions`
(ranked by count, most-chosen first), `reactionCount`, and `myReaction` so the
button reads as already pressed. The same five emoji the room uses on messages —
one set, one vocabulary. Anyone who can see the Moment can react; being in the
room is not required. Cheers are plain by design: they are a count shown to
everyone who can see the Moment, so there is nobody to hide them from.

The Now feed is still ordered by recency. A feed about who is free *right now*
that promoted the busiest rooms instead would be a different feature; the counts
are there, and ranking within them is by count.

## 4. Changing the audience after creation

`PATCH /api/v1/moments/:id/visibility`, host only.

Nothing is migrated or recalculated — every read already derives the audience
from the column — so narrowing removes the Moment from the feeds of people who
no longer qualify at their next read and ejects them from the room the next time
they touch it. Widening announces it to the audience that can now see it, which
is how they learn it exists. Both audiences get `moment.updated`: the old one so
the card goes away, the new one so it appears. The room gets `moment.audience`.

`visibility_changed_at` records that it happened, and the manage screen says
plainly what changing it cannot do: someone who was in the room already read
what was said while they were there.

## 5. What shipped

| | |
|---|---|
| Migration | `027_moments_phase3.sql` |
| API | `POST :id/react`, `PATCH :id/visibility`, envelopes on `POST :id/messages`, per-device room read |
| Android | `MomentRoomState` seals and opens; `RoomCrypto`; reaction row on the Now card; audience chips on the manage screen; lock in the room header |
| Encryption fixes | `E2eeEngine.republish`, `own_identity.publishedAt` (E2EE DB v2), `MessagingRepository.start()`, `SessionManager.onAuthenticationSuccess` |

## 6. Verified

- API typecheck clean; 126 API unit tests pass.
- Android `:app:compileDebugKotlin` and `:app:testDebugUnitTest` pass, including
  five new Moment-room tests (seals for the room, falls back in the clear, opens
  a sealed frame, keeps an unreadable message in place, reaches the server for
  cheers and audience).
- `tools/compose-group-check.js` clean.
- All 27 migrations apply in order on a scratch database on the production
  Postgres, and the new queries run against that schema.

## 7. Not verified here

The API integration suites — including the new
`test/integration/moments-phase3.integration.spec.ts` — need a real Postgres on
`localhost:5432`. There is none on this machine and no local Docker, so they
were written but not run. They are the check that has to pass before this
deploys.

## 8. Still open

- Reactions on room messages are not sealed (§2).
- Web companion is not encrypted.
- Beem "Viro" sender ID still unconfirmed; only two numbers can sign up.
- Light mode remains inert (`ViroColors` vs `colorScheme`); no adaptive layout,
  no localisation, no release signing.
- LiveKit is single-region in France.
