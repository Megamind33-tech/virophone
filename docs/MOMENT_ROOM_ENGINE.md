# Moment Room Engine

One engine renders every Moment. A Moment does not become a different app when
people start cooking, listening or watching: the **room** changes around the
people in it, and the Moment (who is here, how long, its chat) stays.

Status: **Stage A (engine) and Stage B (live presence) done.** Media,
interaction and memory stages are listed at the end with what they still need.

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
* `app/src/test/.../MomentLiveTest.kt` — 11 tests: joins publishing nothing,
  soft failure, a weak network costs the camera and never restores it, short
  dips ignored, quiet room, background, retry with nothing on, nothing
  reconnects after the room ends.

## Next stages

* **C Media** — `MomentMediaProvider`; first provider is media a participant
  owns and uploads. Shared playback is one authoritative record
  (content, status, position at a server time, rate, revision); phones compute
  position locally, never stream positions. Unlocks Watch and Listen. No DRM
  bypass, no rebroadcast, no claimed Spotify/YouTube support.
* **D Interaction** — Touch, Choice, Shared timer.
* **E Memory** — "Keep anything from this?" at the end; nothing kept by default.
