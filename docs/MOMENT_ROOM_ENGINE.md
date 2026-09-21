# Moment Room Engine

One engine renders every Moment. A Moment does not become a different app when
people start cooking, listening or watching: the **room** changes around the
people in it, and the Moment (who is here, how long, its chat) stays.

Status: **Stages A (engine), B (live presence), C (shared media), D (interaction) and E (memory) done.**
Interaction and memory are listed at the end with what they still need.

## Shape of a room

```
Room shell      header (activity, 🔒 host, time left, •••), controls
  Scene         a living background chosen by the activity (KITCHEN, CINEMA…)
  Primary       exactly one module fills the room (PRESENCE, QUIET, VIDEO, MUSIC)
  Secondary     small modules beside it (MUSIC, TIMER, CHOICE)
  Chat          secondary and collapsible, never the centre
```

The shape is a small record the server owns:

```ts
MomentRuntimeState {
  momentId, revision,          // revision only ever increases
  intent,                      // what people said they want to do
  primary, secondary[],        // one primary module, zero or more secondaries
  scene, scenePinned,          // scene follows the intent unless someone pinned it
  updatedAt, updatedBy
}
```

### Intents (what people want to do)

| Intent | Primary | Scene | Secondary |
|---|---|---|---|
| BE, TALK, LEARN | PRESENCE | NEUTRAL | |
| WATCH | VIDEO | CINEMA | |
| LISTEN | MUSIC | LISTENING | |
| PLAY | PRESENCE | PLAY | CHOICE |
| COOK | PRESENCE | KITCHEN | |
| WALK | PRESENCE | OUTDOORS | |
| CHOOSE | PRESENCE | NEUTRAL | CHOICE |
| CELEBRATE | PRESENCE | CELEBRATION | |
| REMEMBER | PRESENCE | FAMILY | |
| STAY | QUIET | QUIET | |

"Something else" creates a Moment in the person's own words with intent BE.
Moments created before intents existed map from their old type
(WATCHING→WATCH, LISTENING→LISTEN, GAMING→PLAY, WORKING→STAY, else BE).

### Changes

`POST /api/v1/moments/:id/state` — participants only.

| op | fields | effect |
|---|---|---|
| TRANSFORM | intent | new primary and scene; secondaries that still fit are kept (QUIET keeps only MUSIC) |
| ADD | module | adds a secondary (or refuses one that can't be secondary) |
| REMOVE | module | removes a secondary |
| SCENE | scene / null | pins a scene; null unpins and returns to the intent's scene |

Errors: 404 not a live Moment you can see, 403 not in the room, 400 a change
this room can't make. Rules live in `apps/api/src/moments/room-engine.ts` as
pure functions, tested without a database.

## Where state lives

* **Redis** `moment:room:<momentId>` — the runtime shape. TTL is the Moment's
  expiry plus 10 minutes. If the key is lost, it is rebuilt from the Moment's
  intent (revision restarts at 1; phones accept an authoritative room fetch).
* **Postgres** — only what the Moment *is*: `moments.intent` (migration 028).
  Room shape is never written to SQL.
* Changes to one Moment are serialized in-process. The API runs as a single
  instance; running replicas would need a Redis lock here.
* Ending a Moment deletes the key.

## Realtime

One event, over the existing signaling websocket (no second socket, no new auth):

```
moment.state  { momentId, state: MomentRuntimeState }
```

sent to every participant after each accepted change. Frames are whole shapes,
not diffs, so a lost frame costs nothing: the next one, or the 15-second
safety refresh, carries the full room.

Phones keep the highest revision they've seen. A late, duplicated or foreign
frame never moves the room backwards. A fetched room (`GET /moments/:id/room`)
is authoritative and wins at equal revision, which is how a phone that was away
— or a server that lost Redis — reconciles. The phone applies its own change
from the HTTP response straight away and ignores the echo.

## Modules on the phone

`app/.../moments/engine/`

```kotlin
interface MomentModule {
    val key: String
    @Composable fun Primary(ctx: MomentRoomContext, modifier: Modifier)
    @Composable fun Secondary(ctx: MomentRoomContext, modifier: Modifier) {}
}
```

`MomentRoomContext` gives a module the room, the Moment, its shape, who is
here, who "me" is, and the clock. Modules register in `MomentModules`; a
module the phone doesn't know renders a quiet "not on this phone yet" panel
instead of crashing, so an older app survives a newer room.

An intent is **offered** only when every module and capability it needs
exists on this phone (`MomentIntent.needs`, `MomentCapabilities`). Nothing is
shown that can't be done: in Stage A that is *Be with me* and *Just stay*.

Scenes (`MomentScene.kt`) crossfade on change and stop drifting when the
system disables animations.

## Live presence (Stage B)

Faces and voices use the LiveKit server calls already use; no second media
stack, no new credentials.

* `POST /moments/:id/presence` → `{url, token, roomName}` for room
  `viro-moment-<id>`. Participants only (403 otherwise, 404 to anyone who
  can't see the Moment, 503 "Live video isn't available right now" when the
  server has no LiveKit). The grant allows **camera and microphone only**: no
  screen share, no data channel. Calls and conferences stay voice-only.
  The token's display name is the person's name, never their id.
* A token only admits, so the server also takes people *out*: leaving, the
  Moment ending (room deleted) and blocks call LiveKit's room service. Best
  effort and non-blocking; the phone also disconnects on the same events.
  `LIVEKIT_API_URL` can point the API at an internal address; otherwise the
  public URL is used over https.
* **Blocks are absolute.** People who have blocked each other never share a
  room: joining is refused with the same "Moment unavailable" a hidden Moment
  gets. A block made during a Moment separates them at once — the host keeps
  their room, otherwise the person blocked leaves — out of the list and out
  of the media room, and their phone is told with `moment.left` for
  themselves, which closes the room there. Leaving twice is not an error.

On the phone (`voice/webrtc/MomentPresenceEngine.kt`, `moments/engine/MomentLive.kt`):

* Entering the room joins its media **publishing nothing**. Microphone and
  camera are buttons, off every time, asked for (Android permission) the
  first time they are pressed. Nothing ever turns either on by itself.
* 360p capture, simulcast, adaptive stream (video nobody is looking at isn't
  downloaded) and dynacast (layers nobody watches aren't sent). DTX + RED on
  voice. Loudspeaker unless headphones are connected.
* Weak network, in order: LiveKit lowers layers first; a *viewer's* weak link
  shows "Video paused — weak connection" on that tile, with the face instead;
  if *this* phone stays weak for 6 s its camera goes off ("Your connection is
  weak, so your camera is off. Your voice carries on.") and **stays off** until
  its person turns it on. A link that gives up is retried every 15 s and comes
  back with nothing on. The room itself never depends on media.
* Viro leaving the screen turns camera and microphone off and says so. A
  quiet room turns the camera off ("Camera off for the quiet."). Ending,
  leaving or being taken out of the room disconnects media.
* With live media, *Talk, Cook, Walk, Learn, Celebrate* and *Remember* are
  offered. Watch, Listen, Play and Help me choose wait for their modules.

Known limits: a Moment's media and a phone call are not coordinated — being
in both at once is untested. Two phones on real networks have not been tried
from this workstation; see the report.

## Watching and listening together (Stage C)

**Where media comes from.** One provider exists: media a participant brings
from their own phone (`UploadedMediaProvider`). The `MomentMediaProvider`
interface is where a licensed source would go, with its own rights checks;
the room, player and sync would not change. Nothing connects to, records or
re-streams Spotify, YouTube, Apple Music or any other service, and the app
never says it does. The share button says: *Share only what you have the
right to share. It plays only for the people here, leaves with you, and is
deleted when the Moment ends.*

**Storage.** `moment_media` (migration 029) holds what was shared: owner,
kind, title, size, duration, file name and a per-file key. Files are checked
against their claimed type by their first bytes (a renamed APK is refused),
capped at 100 MB video / 30 MB audio / 20 items per Moment, and encrypted on
disk with AES-256-CTR under their own key (wrapped by `MESSAGE_ENCRYPTION_KEY`
when set) so any byte range decrypts on its own for seeking. On the VPS they
live on the `viro_reach_moment_media` volume. They are deleted when their
owner leaves, is separated by a block, removes them, or the Moment ends; a
sweep every ten minutes removes files older than an hour that no row refers to.

**Playing.** `POST /moments/:id/media/:mediaId/stream` returns a signed,
short-lived address (`/api/v1/moment-media/:mediaId?u=&e=&s=`) for this person
and item. Each request is rechecked: still a participant, Moment still live.
Byte ranges (206) are served; nothing is cached.

**One clock.** Redis `moment:play:<id>` holds the room's playback:
`{mediaId, kind, title, durationMs, status IDLE|PLAYING|PAUSED, positionMs,
anchorAt (server ms), rate, revision, updatedBy}`. `POST /moments/:id/playback`
takes `LOAD | PLAY | PAUSE | SEEK | STOP`; anyone in the room may press them.
Load starts paused; pause records the position the person pausing saw.
Every change goes to the room as `moment.playback {momentId, playback,
serverNow}` and room reads include `playback`, `serverNow` and `media`.
Positions are never streamed.

On the phone (`SharedPlayback`, `ExoLocalPlayer` on Media3): the server clock
is estimated from request round-trips (midpoint), never from pushed frames.
Expected position = `positionMs + (serverNow − anchorAt) × rate`. Drift under
0.3 s is left alone; 0.3–1.5 s is closed by playing at 0.95× or 1.05×; beyond
1.5 s it jumps. A phone that buffers waits alone, then jumps to the room. Off
screen, a phone goes quiet without pausing anyone and rejoins on return. An
expired address is renewed twice, then the phone says it can't play the file.

The **Watch** room is the film with a small strip of faces; the **Listen**
room is a turning record, the song and who brought it. Music can also sit
beside cooking or talking as a slim bar ("Put some music on").

## Tests

* `apps/api/src/moments/room-engine.spec.ts` — 8 rule tests.
* `apps/api/test/integration/moments-engine.integration.spec.ts` — 9 tests
  with two real signed-in websocket clients: both phones move through
  cook → music → watch → stay in step; reconnect lands in the current room;
  Redis loss; legacy Moments; permissions; concurrent changes; blocks;
  closing clears state.
* `app/src/test/.../MomentRoomEngineTest.kt` — 8 tests: revisions never go
  backwards, foreign frames ignored, reconnect reconciles, own echo ignored,
  refusal leaves the room unchanged, being taken out closes the room, only
  deliverable intents offered.
* `apps/api/test/integration/moments-presence.integration.spec.ts` — 8 tests:
  token scope (camera + mic, no data, no screen, a name not an id), calls stay
  voice-only, non-participants refused, 503 said plainly, leave and end evict,
  a block mid-Moment separates (in the host's room and in a third person's),
  blocked people can't join the same room.
* `apps/api/test/integration/moments-media.integration.spec.ts` — 10 tests with
  two phones on the socket: share → both see it; encrypted on disk; whole and
  ranged reads; fake files refused; only participants; altered or borrowed
  addresses refused; play/pause/seek reach both phones identically; reconnect
  lands where the film is; concurrent presses both count; owner leaving stops
  and deletes; only the owner removes; ending deletes everything; orphan sweep.
* `src/moments/playback.spec.ts` (6) and `moment-media.provider.spec.ts` (2):
  the clock rules; encryption round-trip over arbitrary ranges; file sniffing.
* `app/src/test/.../SharedPlaybackTest.kt` — 11 tests: drift rules, load,
  playing on the server's clock (5 s skew), slow-down/jump/settle, pausing
  where the other person saw it, echo ignored, reconnect mid-film, buffering
  alone, off-screen and back, address renewal and giving up, stop.
* `app/src/test/.../MomentLiveTest.kt` — 11 tests: joins publishing nothing,
  soft failure, a weak network costs the camera and never restores it, short
  dips ignored, quiet room, background, retry with nothing on, nothing
  reconnects after the room ends.

## What a Moment leaves behind (Stage E)

A room is erased when its Moment ends — that has not changed. What changed is
that just before the erasing, the room writes down what it had, and offers it.

Nothing is kept unless somebody says so. The offers expire after 48 hours and
the sweeper removes them, so a Moment that ended while everyone had already
closed the app leaves nothing at all. That is the default, and it is the
behaviour somebody who never answered should get.

What can be offered, all of it metadata:

* **The Moment** — what it was called, or what the activity is called, with
  the people and the date it carries anyway.
* **A decision** — a question that was actually decided, and what was chosen.
  An open question is not a memory.
* **What was played** — the title of something shared to watch or listen to.

The file is *not* kept. It is deleted at closing exactly as before, so keeping
a memory can never quietly become keeping a copy of someone's video. The same
rule is why there is no recording and no transcript.

Keeping is per person. Two people who keep the same evening each hold their
own, and one of them dropping it does not reach into the other's. Titles and
details are encrypted at rest with the rest of message content.

`moment_keepsake_audience` is what makes this possible at all: the room's
participants are deleted the instant it closes, and the right to answer an
ending has to outlive them. It is also the record of who was there, which is
itself part of what the memory is — a Moment is not "cooking", it is
"cooking with Natasha".

### Next

* **A game module** — "Play with me" asks for a GAME module that does not
  exist, so it is not offered. Nothing else is waiting on it.
* Room-message reactions are still readable by the server; see
  `encryption-design.md` §12.
