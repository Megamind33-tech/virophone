-- Blocking and spam marking for contacts that are NOT Viro users.
--
-- The existing `blocks` table is keyed by blocked_user_id, so it can only hold
-- a block against someone who already has an account. Blocking a contact who
-- has not joined Viro therefore did nothing server-side and never appeared in
-- the blocked list — the block was written to the handset only, and the list is
-- read from the server.
--
-- These live on contact_preferences rather than in a new table because they are
-- the same kind of fact, keyed the same way (account + phone number), and they
-- should follow the user to a new phone exactly as favourites now do. A block
-- someone loses when they change handset is not much of a block.
ALTER TABLE contact_preferences
    ADD COLUMN IF NOT EXISTS is_blocked BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE contact_preferences
    ADD COLUMN IF NOT EXISTS is_spam BOOLEAN NOT NULL DEFAULT FALSE;

-- Blocked and spam numbers are read on every contact list render, so they are
-- worth an index even at small scale.
CREATE INDEX IF NOT EXISTS idx_contact_preferences_blocked
    ON contact_preferences(user_id) WHERE is_blocked = TRUE;

CREATE INDEX IF NOT EXISTS idx_contact_preferences_spam
    ON contact_preferences(user_id) WHERE is_spam = TRUE;
