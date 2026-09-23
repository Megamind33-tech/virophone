-- Remembering whether a closed Moment was actually cleared up.
--
-- Ending a Moment marks it terminal first and then erases the room: the
-- participants, the messages, the knocks, the shared files, the Redis keys and
-- the LiveKit room. None of that can be held inside one transaction, because
-- most of it is not the database.
--
-- So if any step failed, the Moment was already ENDED and nothing ever came
-- back for the rest — the sweep only looks at ACTIVE rows. The leftovers stayed
-- for good: messages from a Moment that no longer exists, files nobody can
-- reach, and Redis keys describing a room that is over.
--
-- This column is the difference between "marked over" and "cleared up", so the
-- sweep can finish what an earlier attempt did not.
ALTER TABLE moments ADD COLUMN IF NOT EXISTS cleaned_at timestamptz;

-- Everything already terminal was cleaned by the old path, or is old enough
-- that retrying would delete nothing. Marking it keeps the sweep's work to
-- what actually needs it rather than every Moment ever held.
UPDATE moments SET cleaned_at = COALESCE(ended_at, expires_at, now())
  WHERE cleaned_at IS NULL AND status <> 'ACTIVE';

-- The sweep asks for exactly this: terminal, not yet cleared up.
CREATE INDEX IF NOT EXISTS moments_needing_cleanup_idx
  ON moments (expires_at) WHERE cleaned_at IS NULL AND status <> 'ACTIVE';
