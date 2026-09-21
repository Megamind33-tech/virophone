# End-to-end encryption for Viro — design and decisions

Status: Stages 0 to 3 are built. One-to-one chats encrypt end to end in
production from 2026-09-21 (`E2EE_ENABLED=true`): text, files, voice notes,
photos, polls, places and edits. Groups and the web companion are not
encrypted yet, and nothing in the app claims they are. Sections 8, 9 and 10
record what each stage actually does; sections 1 to 7 are the original design
note, kept because the trade-offs in it are the ones that were taken.

Today Viro encrypts traffic in transit (HTTPS/WSS) and calls are peer-to-peer
media, but **messages are stored readable on the server**: `messages.body` and
`messages.metadata` are plain columns in Postgres, and uploaded files sit
unencrypted in the media directory. Anyone with the database, a backup of it,
or root on the VPS can read everyone's messages. That is the gap this document
is about.

## 1. What end-to-end encryption would and would not protect

**Would protect** — message text and files against: the server being
compromised, a stolen database backup, an insider with production access, and a
legal demand for message content. The server would hold only ciphertext it
cannot open.

**Would not protect** — anything on an unlocked phone, screenshots, a person
forwarding a message, or the *metadata* that makes the service work.

**What the server still sees, with or without E2EE.** This has to be said
plainly, because "encrypted" is often heard as "invisible":

- who is talking to whom, and when (`messages.sender_user_id`, `conversation_id`, timestamps)
- who is in which group, and when they joined or left
- message sizes, file sizes and kinds, and how often people message
- phone numbers, emails, Viro IDs, contact matches and connections
- presence and last seen

Viro's relationship features (targets, cadence, timeline, Loop reminders) read
exactly this — `relationships.service.ts` queries message *timestamps and
senders*, never bodies. **They keep working under E2EE unchanged**, which is
worth knowing: the most distinctive part of the product is not in the way.

## 2. What breaks, and what it costs

Each of these is a place the server reads plaintext today. This is the real
price of E2EE, and it should be decided deliberately rather than discovered.

| Feature | Today | Under E2EE | Cost |
|---|---|---|---|
| **Search** (`messages.service.search`) | Server does an ILIKE across bodies, so it finds messages older than the phone keeps | Server can't. Search becomes "what is on this phone" | Real loss. The app already searches locally first; the server half disappears |
| **Voice-note transcripts** (Whisper container) | Server transcribes the audio file | Server can't read the file. Either drop for encrypted chats, or run on-device later | Feature lost for now, or a large on-device build |
| **Link previews** | Server fetches the URL and stores title/description | Sender's phone fetches and attaches it inside the encrypted message | Modest work; also *better* for privacy (server stops seeing which links people share) |
| **Push notification text** | Server composes "Alice: see you at 3" | Server can only say "New message"; the phone decrypts and replaces it | Android can do this (we already wake on push and sync). A locked phone briefly shows less |
| **Polls** | Server counts votes | Votes become encrypted messages; each phone counts | Moderate rework of `vote()` and poll display |
| **Loop answers** | Server holds answers and enforces reciprocal reveal | Reveal logic must move to the phones, or Loops stay outside E2EE and are labelled | Design decision, section 6 |
| **Web companion** | Reads messages from the API | Needs its own keys and its own copy of the session state; the current no-build-step page cannot do libsignal | Either a bundled web app, or the web shows only what the phone relays while online |
| **Scheduled / disappearing** | Server holds the message until `deliver_at`, expires by timestamp | Still fine — both work on metadata, not content | None |
| **New phone / reinstall** | History re-syncs from the server | Server can't hand over readable history. Needs an encrypted backup with a recovery key | Significant: people lose history if they lose the key |
| **Media** | Files stored plain on disk | Encrypted by the sender with a per-file key carried inside the message | Modest; `MediaStore` becomes opaque-byte storage |

## 3. The design, if we do it properly

Standard, boring, well-understood — no invented cryptography.

- **Identity**: each *device* gets a long-term identity key pair. The phone and
  each linked computer are separate devices with separate keys.
- **Key distribution**: each device uploads a signed prekey and a batch of
  one-time prekeys. The server stores and hands them out; it never sees private
  keys. New table `device_keys`, plus rotation.
- **Session setup**: X3DH between sender device and each of the recipient's
  devices.
- **Message encryption**: Double Ratchet per session (forward secrecy, and
  recovery if a key leaks).
- **Groups**: sender keys — each member encrypts once per group with a sender
  key distributed pairwise, rather than N pairwise messages per send.
- **Media**: random per-file key, AES-GCM, ciphertext uploaded to the existing
  media endpoint, key inside the encrypted message.
- **Verification**: a safety number per contact, shown in contact details, with
  a warning when someone's keys change — this is what makes E2EE meaningful
  rather than a claim.
- **Library**: `libsignal-client` (the audited implementation). Android has a
  Java/Kotlin binding. **Not** hand-rolled primitives.

### Multi-device is the hard part

We shipped the web companion in 0.4.71. Under E2EE it can work two ways:

1. **Its own keys** (what WhatsApp does now). The computer is a real device with
   its own identity, added to every session. Needs libsignal in the browser
   (WASM) and therefore a build step and key storage in the browser — a
   different, heavier web app than the single page we have.
2. **Phone relays** (old WhatsApp Web). The computer holds no keys; the phone
   decrypts and forwards. Simpler and safer, but the computer only works while
   the phone is online and reachable.

## 4. An honest interim step (cheap, real, not E2EE)

Encrypting message bodies and media **at rest** on the server, with a key held
outside the database (env/KMS), closes the most likely real-world leak: a
database dump, a stolen backup, a misconfigured snapshot. It changes no
features and takes days, not weeks.

It does **not** protect against someone who has root on the running server, and
it must never be described as end-to-end. If we do it, the wording in the app
should be exactly: "encrypted in transit and stored encrypted — not yet
end-to-end."

## 5. Suggested order of work

Each stage ships and is testable on its own.

- **Stage 0 — at-rest encryption** — **done** (2026-09-20). `messages.body`,
  `messages.metadata` and media files are AES-256-GCM encrypted with a key held
  in the environment. Rows written earlier still read, and `dist/scripts/encrypt-backfill.js`
  converts them. Production refuses to start without the key. Mentions moved to
  their own table, because encrypted metadata cannot be searched. Not end-to-end:
  the server holds the key.
- **Stage 1 — keys and one-to-one text** (large, in progress). Device keys,
  prekeys, sessions, ratchet, safety numbers, key-change warnings. Search goes
  local-only for encrypted chats; push falls back to "New message" then
  decrypts on the phone. See section 8 for what is built and what it costs.
- **Stage 2 — media and groups** (large). Encrypted files, sender keys for groups.
- **Stage 3 — history that survives a new phone** (medium). Encrypted backup with
  a recovery key the person keeps, and a clear warning that losing it loses
  history.
- **Stage 4 — the web companion under E2EE** (medium/large, depending on 3.1 vs 3.2).

Transcripts stay off for encrypted chats until (and unless) on-device
transcription is worth its size.

## 6. Decisions needed before any code

1. **How far, and when?** Stage 0 now and Stage 1 after, or straight to Stage 1?
2. **Search**: accept that searching only reaches what's on the phone for
   encrypted chats?
3. **Voice transcripts**: accept losing them in encrypted chats for now?
4. **Web companion**: its own keys (heavier web app), or phone-relay (only works
   while the phone is online)?
5. **History on a new phone**: encrypted backup with a recovery key the person
   must keep — and accept that losing it means losing history?
6. **Loops**: move the reciprocal-reveal logic onto the phones, or keep Loops
   outside E2EE and say so in the UI?
7. **Scope**: everything, or one-to-one chats first and groups later?

## 7. Recommendation

Do **Stage 0 now** and **Stage 1 next**, and say precisely what is true at each
point rather than promising encryption early.

The reason is not timidity about the crypto — the library is standard and the
protocol is well understood. It is that Viro's value is the relationship
system, which is unaffected, while E2EE mostly costs *other* features people
use daily: server-side search, transcripts, rich notifications, and history on
a new phone. Stage 0 removes the most likely real leak in days. Stage 1 then
earns the word "end-to-end" honestly, on one-to-one chats first, with safety
numbers so it can be verified rather than trusted.

## 8. Stage 1 as built

### The shape of it

- **Key directory** (`device_identity_keys`, `device_one_time_prekeys`). Each
  device publishes an identity key, a signed prekey, a Kyber prekey and a batch
  of one-time prekeys. The server hands each one-time prekey out **once**, in a
  single `UPDATE … RETURNING` so two senders cannot receive the same one.
  Asking for someone's *device list* costs no prekey; a sender fetches full
  bundles only for devices it has no session with yet.
- **Envelopes** (`message_envelopes`). An encrypted message is stored once per
  recipient device: the row in `messages` has type `ENCRYPTED`, no body and no
  metadata. Each device collects its own copy. Envelopes die with their message
  and with the device they were addressed to.
- **On the phone**: libsignal, with its state in `viro_e2ee.db` — a database
  that is never destructively migrated, because nothing in it can be fetched
  again.

### Two invariants

1. **A chat that has gone encrypted cannot quietly go back.** The server
   refuses a plaintext send into an encrypted conversation, so an old build or
   the web companion cannot drop one readable message into it.
2. **Nobody in the chat may be left without a copy.** A send that does not
   cover every participant is refused: a message half the room cannot open is
   worse than no message.

### What it costs, concretely

- **The web companion is read-only in encrypted chats.** It holds no keys of
  its own (that is Stage 4), so it shows "Encrypted message — open it on your
  phone" and disables the composer there. This is a real regression for a
  feature shipped in 0.4.71, and it is the price of the promise.
- **Server-side search skips encrypted messages**, as decided. The phone still
  searches what it holds.
- **Link previews and message effects are dropped** in encrypted chats: they
  live in metadata, which is not stored for sealed messages. The sender will
  carry them inside the ciphertext in a later stage.
- **History does not follow you to a new phone.** An envelope can be opened
  once, by the device it was addressed to. Stage 3 (encrypted backup with a
  recovery key) is what fixes this.
- **Download size**: libsignal ships its native library with debug symbols —
  about 60 MB per architecture, and no NDK on the build machine means AGP
  cannot strip it. Three things keep the download sane: only the two ARM
  architectures (real phones), the libraries stored compressed rather than
  page-aligned, and the library's desktop builds — which ride along inside its
  jar — excluded from packaging. The debug APK went from 114 MB before
  encryption to 79 MB after, because dropping the two x86 architectures pays
  for libsignal twice over. Installing an NDK would strip the symbols and cut
  it further; that is the better fix when there is bandwidth for it.

### Why libsignal 0.72.0 and not the newest

From 0.74.1 on, libsignal is compiled with Kotlin 2.1, whose metadata the
Kotlin 1.9.22 compiler in this project refuses to read. 0.72.0 is the newest
release that is still pure Java: it has PQXDH (the Kyber prekey is mandatory in
a bundle) but not the newer post-quantum *ratchet*. Moving past it means
moving the whole app to Kotlin 2.x — which also means replacing the Compose
compiler setup that caused the 0.4.62 crash, so it is its own piece of work.

### Why it ships switched off

There is one more consequence of invariant 1 that only becomes obvious when you
follow it through: **a chat that has gone encrypted refuses every message type
Stage 1 cannot seal.** That is photos, voice notes, files, polls, places,
shared contacts and Loop answers — everything that lives in `metadata` or in
the media store. Turning encryption on for real chats before Stage 2 would mean
photos quietly stop working in exactly the chats people were told were safest.

So Stage 1 ships complete but dormant: keys are generated and published, the
server carries envelopes, safety numbers work — and `GET /messages/features`
reports `e2ee: false` until `E2EE_ENABLED=true` is set. Phones only start
encrypting new chats when that flag says so, and a chat that is already
encrypted keeps working either way. Stage 2 — sealed media and the remaining
message types, then groups — is what makes the flag safe to turn on.

## 9. Stage 2: everything else a message can be

Stage 1 could only seal text, and an encrypted chat refuses anything the
server would have to store in the clear — so photos and voice notes would have
stopped working in exactly the chats that were meant to be safest. Stage 2 is
the rest of it.

**A message carries its own shape.** What used to be plain text inside the
ciphertext is now a small record: the type, the body, the metadata (a poll's
question, a shared contact, a place, a GIF), and, when there is a file, the key
that opens it. The server keeps type `ENCRYPTED` for all of them, so it cannot
even tell a photo from a place — only that a message exists and that there are
bytes behind it.

**Files get their own key.** Each one is encrypted with AES-256-GCM under a key
used once, uploaded as bytes, and the key travels inside the message. The
server stores nothing describing it: no mime type, no name, no duration,
waveform or dimensions — all of that is content and goes inside. Decryption is
streamed on the phone, because a document can be 25 MB.

**Polls are counted without being read.** Votes stay server-side as option
*numbers*; the question and the options come out of the sealed message, and the
phone puts the two together. The server learns that someone chose option 2, and
nothing about what option 2 says.

**A live location is swapped, not tracked.** Each new position is sealed again
for every device and replaces what each is holding. The one thing the server
knows is when the share ends, because the server is what stops carrying it.

### What is still not encrypted, and is said so in the app

- **Reactions and Loop answers were the two gaps here, and both are closed.**
  A reaction is now a sealed message the phones apply; a Loop answer is sealed
  per device and the server withholds the ciphertext exactly as it used to
  withhold the words. See section 11.
- **Link previews are switched off in encrypted chats**, because asking the
  server what a link looks like tells it which link is about to be sent. The
  link still goes, as text.
- **Groups.** Sender keys are the next piece of work; group chats are not
  encrypted yet, and nothing claims they are.

## 10. Stage 3: a backup the server cannot open

Encryption made the phone the only place a message exists in readable form.
That is the point of it — and it means a lost or reinstalled phone loses every
conversation. Stage 3 is what stops that being true.

**How it works.** The phone gzips its own message store, encrypts it with
AES-256-GCM under a 32-byte **recovery key**, and uploads the result. The
server records how big it is and when it arrived, hands it back to a phone that
asks, and cannot do anything else with it: there is no key on the server, and
no code path that opens an archive.

**The recovery key is the whole story.** It is shown once, as sixteen groups of
four characters from an alphabet with no I, L, O or U — so it can be written on
paper and typed back without ambiguity. The phone keeps a copy in encrypted
preferences so backups can run unattended. Viro has no copy, and the screen
that shows the key says exactly that, in those words, before anyone needs it.

**Media is deliberately not in the archive.** A sealed file is already on the
server as bytes, and the key to it comes back inside the message that carried
it — so restoring the messages restores the photos too, without paying to
upload them twice.

**What a restore gives back.** Conversations and messages as of the last
backup, added to whatever is on the phone rather than replacing it. Anything
left in the old phone's outbox is marked failed rather than sent again: it
either went already, or it never will.

**What it does not give back.** A new phone gets a new identity key, which is
right — the other person's safety number changes, and they are told. Restoring
history is not the same as restoring a device.

**When it runs.** Once a day, on an unmetered connection and while charging —
an archive is not worth spending someone's data bundle or their last ten per
cent on. The backup screen has "Back up now" for anyone who would rather not
wait for the conditions.

## 11. Closing the gaps: groups, reactions, Loops

**Groups.** A group message is sealed once per member device — the same
machinery as a one-to-one chat, with the audience being everyone in the room.
Sender keys would make it one ciphertext for the whole group instead, and are
the obvious optimisation if groups here ever get large; this is the same
promise, paid for in bandwidth rather than in complexity, and it works today.
The envelope ceiling is 512, which covers the largest group the app allows.
A group where one person's app cannot decrypt stays in the clear until it can,
because half a room is not a room.

**Reactions.** Which emoji someone chose says something, so it could not stay a
row the server reads. A reaction is now a sealed message that the phones apply
to the message it belongs to. The server is told one thing about it — that it
is not a message — so there is no notification, no unread badge, and it never
becomes the line shown in the inbox. That flag is honoured only for sealed
sends; in the clear a reaction has its own endpoint and needs no disguise.

**Loop answers.** A Loop's promise is that neither of you sees the other's
answer until you have both answered, and the server is what makes that true: it
holds the answers and decides when to hand them over. That did not have to mean
it could read them. An answer is now sealed per device, and the server goes on
withholding it under exactly the same rule — what changes is that the thing
being withheld is ciphertext, and stays ciphertext afterwards. Photo and voice
answers are sealed like any other file, with their own key.

The reveal rule is still enforced by the server rather than by the phones,
which was the alternative in section 6. That is deliberate: the rule is about
fairness, not confidentiality, and moving it onto the phones would not make the
answers any more private than they now are — it would only mean two clients
arguing about who answered first.
