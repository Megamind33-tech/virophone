-- Phase 3 Viro Now: the three things Phase 2 left open — a sealed room chat,
-- a reaction to the Moment itself, and an audience the host can change after
-- the Moment is already running.

-- 1. Room chat, end to end.
--
-- A room message now carries either a body the server can read or one sealed
-- copy per device and no body at all; never both. Phase 2 stored only the
-- plaintext, which meant the one place in Viro where several people talk at
-- once was also the one place the server could read. Sealing is per device,
-- the same shape as message_envelopes for ordinary chats, because a room has
-- no long-lived group key to ratchet — it exists for at most two hours and its
-- membership changes while people are talking.
--
-- body loses NOT NULL rather than gaining a default: an empty string would be
-- a message that reads as blank to any older client, where NULL is understood
-- as "this one is sealed, look at its envelopes". The existing CHECK stays as
-- it is — it passes on NULL, and still rejects a blank body.
ALTER TABLE moment_messages ALTER COLUMN body DROP NOT NULL;
-- Which device said it. A sealed copy can only be opened against the session
-- with the device that sealed it, so the reader has to be told; for a
-- plaintext message it is simply unused.
ALTER TABLE moment_messages ADD COLUMN sender_device_id uuid REFERENCES devices(id) ON DELETE SET NULL;

CREATE TABLE moment_message_envelopes (
  message_id uuid NOT NULL REFERENCES moment_messages(id) ON DELETE CASCADE,
  device_id uuid NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  ciphertext text NOT NULL,
  envelope_type smallint NOT NULL DEFAULT 1,
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (message_id, device_id)
);
-- Read path: "the copies addressed to this device, in this room".
CREATE INDEX moment_message_envelopes_device ON moment_message_envelopes (device_id, created_at DESC);

-- 2. A reaction to the Moment itself.
--
-- Separate from moment_reactions, which reacts to a message inside the room:
-- this one is on the Moment, and it is what someone who has not joined can
-- still say. One live reaction per person, so re-reacting switches the emoji
-- and clearing removes the row; the counts are then just a GROUP BY.
CREATE TABLE moment_cheers (
  moment_id uuid NOT NULL REFERENCES moments(id) ON DELETE CASCADE,
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  emoji varchar(16) NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (moment_id, user_id)
);
CREATE INDEX moment_cheers_moment ON moment_cheers (moment_id);

-- 3. Changing the audience after creation.
--
-- Only when it last changed is new: the visibility column already exists and
-- every read re-derives who may see the Moment from it, so widening or
-- narrowing takes effect on the next read for everyone, including people
-- already sitting in the room. This records that it happened, for the "changed
-- to Connections only" line the room shows.
ALTER TABLE moments ADD COLUMN visibility_changed_at timestamptz;
