-- 030: what people choose to keep from a Moment.
--
-- A Moment ends and the room is erased — that is the promise, and it does not
-- change here. What changes is that just before the erasing, the room offers
-- what it had: the evening itself, what was decided, what was listened to.
-- Nothing is kept unless somebody says so. There is no recording, no
-- transcript, and nothing is kept on anyone's behalf.
--
-- Deliberately metadata only. "We watched this" is kept; the file is not. The
-- room's files are deleted at closing exactly as before, so keeping a memory
-- cannot quietly become keeping a copy of someone's video.

-- When the room actually closed, so the ending can say how long people were
-- together rather than which minute the row expired.
ALTER TABLE moments ADD COLUMN IF NOT EXISTS ended_at timestamptz;

-- Who may keep something from this Moment.
--
-- moment_participants is deleted the instant the room closes, and the right
-- to keep has to outlive it: people decide after the ending, not during it.
-- This is also the record of who was there, which is itself part of what a
-- memory is — a Moment is not "cooking", it is "cooking with Natasha".
CREATE TABLE IF NOT EXISTS moment_keepsake_audience (
  moment_id uuid NOT NULL REFERENCES moments(id) ON DELETE CASCADE,
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  PRIMARY KEY (moment_id, user_id)
);

-- What COULD be kept, gathered as the room closes.
--
-- An offer is not a memory. It expires, and the sweeper removes it: a Moment
-- nobody answered for leaves nothing behind, which is the behaviour someone
-- who simply closed the app should get.
CREATE TABLE IF NOT EXISTS moment_keepsake_offers (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  moment_id uuid NOT NULL REFERENCES moments(id) ON DELETE CASCADE,
  kind varchar(16) NOT NULL CHECK (kind IN ('MOMENT', 'DECISION', 'MEDIA')),
  -- Encrypted at rest with the rest of message content: a decision someone
  -- made together is the same kind of private as a message about it.
  title text NOT NULL,
  detail text NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  expires_at timestamptz NOT NULL
);
CREATE INDEX IF NOT EXISTS moment_keepsake_offers_moment ON moment_keepsake_offers (moment_id);
CREATE INDEX IF NOT EXISTS moment_keepsake_offers_expiry ON moment_keepsake_offers (expires_at);

-- What somebody actually kept. Theirs, not the room's.
--
-- One row per person per thing: two people who keep the same evening each
-- hold their own copy, and one of them deleting it does not reach into the
-- other's. Who was there is read from the audience above rather than copied
-- here, so a name is always the current one.
CREATE TABLE IF NOT EXISTS moment_keepsakes (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  moment_id uuid NOT NULL REFERENCES moments(id) ON DELETE CASCADE,
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  offer_id uuid NULL REFERENCES moment_keepsake_offers(id) ON DELETE SET NULL,
  kind varchar(16) NOT NULL CHECK (kind IN ('MOMENT', 'DECISION', 'MEDIA')),
  title text NOT NULL,
  detail text NULL,
  kept_at timestamptz NOT NULL DEFAULT now(),
  -- Keeping the same thing twice is the same keepsake, not two.
  UNIQUE (user_id, offer_id)
);
CREATE INDEX IF NOT EXISTS moment_keepsakes_owner ON moment_keepsakes (user_id, kept_at DESC);
