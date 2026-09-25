# Encrypted message delivery — "Waiting for this message" fix

Chats were filling with "🔒 Waiting for this message" bubbles, for new and old
messages, incoming and outgoing. This records why, what changed, and how to
verify it on two phones.

## What Viro actually uses

- **Protocol:** libsignal 0.72 — X3DH with a Kyber prekey to start a session,
  then the Double Ratchet. One session per remote *device*
  (`SignalProtocolAddress(deviceId, 1)`); every message is sealed once per
  recipient device, plus the sender's own other devices.
- **Key store:** `core/e2ee` — Room database `viro_e2ee.db` (identity,
  sessions, prekeys), driven by `E2eeEngine` / `ViroSignalStore`.
- **Messages:** `core/database` — Room database `viro_messaging.db`;
  `MessagingRepository` owns send, receive, sync, history and the outbox.
- **Transport:** the signalling WebSocket delivers `message.new` /
  `message.updated`; `GET /messages/sync?since=` and
  `GET /messages/conversations/{id}` (history) return the same message shape,
  envelopes included. The server keeps every envelope; "delivered" is only a
  receipt and never consumes anything.
- **Device identity:** the server issues a new device id at every OTP
  sign-in; the phone generates a new identity for it once, and never on an
  ordinary launch, reconnect or resume.

## Root causes

1. **Ciphertext was thrown away.** When a sealed message could not be opened,
   it was stored as `type = ENCRYPTED` with no envelope and no sender device.
   The sync cursor had already moved past it, so nothing ever tried again.
   Every transient failure became permanent.
2. **Two copies of one message raced.** The socket frame and the sync (on
   reconnect, and the open chat's backstop poll) ingested the same message at
   the same time. One opened it and moved the ratchet on; the other failed to
   open it again (`DuplicateMessageException`) and wrote the sealed copy over
   the readable one.
3. **The sender's own message was overwritten.** After a send, the outbox row
   was deleted and the sent row written in two steps. The server echoes the
   message back to the sending phone, sealed only for other devices. Landing
   between those two steps, it found nothing, could not open it, and wrote
   "Waiting" over words the person had just typed.
4. **A crash could spend a key.** libsignal saves the advanced ratchet before
   the chat row exists. A kill in between left a message that could never be
   opened.
5. **Stale device lists.** Senders trusted a person's device list for five
   minutes. Anything sent to someone who had just signed in again was sealed
   only for the phone they no longer had.
6. **Every failure looked the same.** `open()` returned `null` for all of
   the above, so nothing could tell "try again shortly" from "never".

A related fault on the same path: recipients never saw edits to encrypted
messages, because an already-opened row was never re-opened.

## What changed

- **One ingestion path, serialised.** Live frames, sync, history, search and
  retries all go through `upsertMessages`, now under a single lock and in
  server order. The sent-message write shares that lock.
- **Nothing is dropped.** A message that cannot be opened yet keeps its row,
  in its place, with `cryptoState = PENDING`. Its ciphertext goes into a
  durable `pending_decryption` queue (`MessagingDatabase` v6, migrated).
- **Classified failures.** `E2eeEngine.openMessage` returns
  `Opened | Failed(DecryptFailure)`, mapped from libsignal's exceptions.
  `MISSING_SESSION`, `SESSION_MISMATCH`, `UNKNOWN_PREKEY`, `NOT_REGISTERED`
  and `TRANSIENT` are retried. `ALREADY_OPENED`, `UNSUPPORTED_VERSION` and
  `CORRUPTED` are final.
- **Retries on events, with restrained backoff.** Retries run when:
  - a new session is set up with the sender's device;
  - registration finishes;
  - a sync completes, including after every WebSocket reconnect;
  - a chat is opened;
  - scheduled backoff comes due: 5 s, 15 s, 1 min, 5 min, 15 min, then
    hourly. Scheduled retries stop only after 8 attempts *and* a full day.
- **Crash-safe opening.** The ratchet step and the plaintext are committed in
  one transaction (`opened_messages`, `E2eeDatabase` v3), handed to the chat
  database, then dropped.
- **Final states said plainly.** A message sealed only for other devices (sent
  before this phone was signed in), or whose key is already spent, is marked
  `UNAVAILABLE` and reads "Not available on this phone". It is never shown as
  waiting.
- **One-off repair.** Old `ENCRYPTED` rows with no stored ciphertext are
  re-fetched once from history and put through the same path.
- **Device lists** are trusted for 60 s instead of 5 min. A message from a
  device the phone did not know about invalidates that person's cached list
  at once.
- **Prekeys** are topped up after any message that starts a session, not
  only at launch.
- **Presentation.** Pending messages show a muted "Decrypting message…" in
  their own place, with no lock icon, and change to the real message in place.
  Notifications wait for the words.
- **Edits** to encrypted messages now open on the recipient's phone.

Encryption is unchanged: nothing is sent or stored in the clear on the
server, no key leaves the phone, and no identity is regenerated outside
sign-in.

## Follow-up: asking the author's phone to seal it again

The first round stopped messages being lost, but anything already broken —
a key spent by the old race, a message sealed before a phone's latest sign-in
— could only be marked "Not available on this phone". Real two-phone tests
(`core/e2ee/.../TwoPhonesTest.kt`, 13 cases on real libsignal) confirmed that
new messages between two healthy phones open correctly, so what testers saw
was this backlog.

Now a phone that cannot open a message asks its author's phone for it, the
way Signal does:

1. The recipient calls `POST /messages/:id/resend-request` — identifiers only.
   The server passes it to the author's phones as `message.resend-request`.
2. The author's phone still holds the words. It seals the same payload again
   for that one device, on a fresh session: the old session is archived, not
   deleted, so anything already on its way still opens. It then calls
   `POST /messages/:id/envelopes`, which is author-only, only for devices in
   the chat, and never marks the message as edited. That bumps `updated_at`,
   so an offline phone's next sync collects it.
3. The recipient opens it through the ordinary path, in its place.

A recipient asks when:

- its key is spent;
- the message was not addressed to this device;
- the copy is damaged;
- a session keeps refusing a message after three retries.

It asks again at most hourly for two days, showing "Getting this message from
their phone…". Only after that does it say "Not available on this phone".
Messages the previous build had already marked unavailable are put back in
line once. A burst of requests shares one fresh session per device.

Only the author can re-seal, so nobody can forge someone else's words. A
message this very phone sent and then lost has no one to ask, and stays
unavailable.

**Needs the API deployed** (`scripts/vps-deploy.sh`) for the two new endpoints.
Until then requests fail quietly and messages stay pending.

## Debug diagnostics

Debug builds log one line per state change under the tag `ViroE2eeMsg`,
with identifiers only. Bodies, keys and ciphertext are never logged:

```
msg=… conv=… senderDevice=… dir=in source=websocket crypto=PENDING_DECRYPTION reason=MISSING_SESSION attempt=1
msg=… conv=… senderDevice=… dir=in source=retry:session crypto=DECRYPTED
```

`adb logcat -s ViroE2eeMsg E2ee` shows the whole story of a message.

## Two-device test matrix

Run on two real phones, both on this build. Pass means every message ends up
readable, in order, with no "Decrypting message…" left behind after a few
seconds online.

| # | Scenario | Expect |
|---|---|---|
| 1 | Both online, A → B | Readable immediately on both |
| 2 | B offline, A sends several; B reconnects | All readable, in order |
| 3 | B's app killed, A sends; B starts the app | Recovered without opening the chat |
| 4 | A sends 10 quickly | None stuck |
| 5 | A and B alternate rapidly | No stuck messages either side |
| 6 | Switch Wi-Fi ↔ mobile data mid-chat | Conversation continues |
| 7 | Airplane mode on/off (socket reconnect) | No resets; safety number unchanged |
| 8 | Reopen an old conversation | Earlier messages still readable |
| 9 | Scroll up to load older messages | Correct order, scroll position kept |
| 10 | Restart both apps | Conversation readable |
| 11 | Send after restart | Opens normally |
| 12 | B signs out and back in (new device), A sends | B gets a one-time safety-number notice in the chat, then readable messages; messages from before the new sign-in read "Not available on this phone" |

The first launch of this build repairs old stuck rows. Some of those may
legitimately end as "Not available on this phone": their keys were spent
before this fix and cannot be recovered without weakening encryption.
