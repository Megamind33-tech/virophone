# Moment Room Engine

One engine renders every Moment. A Moment does not become a different app when
people start cooking, listening or watching: the **room** changes around the
people in it, and the Moment (who is here, how long, its chat) stays.

Status: **Stage A (engine) done.** Presence, media, interaction and memory
stages are listed at the end with what they still need.

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

## Tests

* `apps/api/src/moments/room-engine.spec.ts` — 8 rule tests.
* `apps/api/test/integration/moments-engine.integration.spec.ts` — 9 tests
  with two real signed-in websocket clients: both phones move through
  cook → music → watch → stay in step; reconnect lands in the current room;
  Redis loss; legacy Moments; permissions; concurrent changes; blocks;
  closing clears state.
* `app/src/test/.../MomentRoomEngineTest.kt` — 7 tests: revisions never go
  backwards, foreign frames ignored, reconnect reconciles, own echo ignored,
  refusal leaves the room unchanged, only deliverable intents offered.

## Next stages

* **B Presence** — a Moment-only LiveKit token (camera + microphone, room
  `viro-moment-<id>`, participants only), live tiles, camera and mic asked for
  only when someone chooses them, video → audio → presence on a weak network.
  Unlocks Talk, Cook, Walk, Learn, Celebrate, Remember.
* **C Media** — `MomentMediaProvider`; first provider is media a participant
  owns and uploads. Shared playback is one authoritative record
  (content, status, position at a server time, rate, revision); phones compute
  position locally, never stream positions. Unlocks Watch and Listen. No DRM
  bypass, no rebroadcast, no claimed Spotify/YouTube support.
* **D Interaction** — Touch, Choice, Shared timer.
* **E Memory** — "Keep anything from this?" at the end; nothing kept by default.
