# End-to-end encryption for Viro — design and decisions

Status: Stage 0 (encryption at rest) is built and deployed. Stage 1 (keys and
one-to-one text) is being built now — the server half is in. Stages 2-4 are
still a proposal. Written 2026-09-20, against commit `c431676`.

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
- **Download size**: libsignal's native library adds roughly 14 MB per
  architecture. Release builds are limited to `arm64-v8a` and `armeabi-v7a`,
  and the library's desktop builds — which ride along inside its jar — are
  excluded from packaging.

### Why libsignal 0.72.0 and not the newest

From 0.74.1 on, libsignal is compiled with Kotlin 2.1, whose metadata the
Kotlin 1.9.22 compiler in this project refuses to read. 0.72.0 is the newest
release that is still pure Java: it has PQXDH (the Kyber prekey is mandatory in
a bundle) but not the newer post-quantum *ratchet*. Moving past it means
moving the whole app to Kotlin 2.x — which also means replacing the Compose
compiler setup that caused the 0.4.62 crash, so it is its own piece of work.
