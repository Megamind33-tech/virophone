-- 016: an About line, a real "last seen", and who may see either.
--
-- about: a short line people write about themselves ("At work", "Available").
-- *_visibility: EVERYONE | CONTACTS | NOBODY. CONTACTS means someone who has
--   this person in their address book, or is a connection — the same people
--   who can already reach them.
-- last_seen_at: when they were last connected. Written at most once a minute.

ALTER TABLE profiles ADD COLUMN IF NOT EXISTS about VARCHAR(139) NULL;
ALTER TABLE profiles ADD COLUMN IF NOT EXISTS about_visibility VARCHAR(16) NOT NULL DEFAULT 'EVERYONE';
ALTER TABLE profiles ADD COLUMN IF NOT EXISTS photo_visibility VARCHAR(16) NOT NULL DEFAULT 'EVERYONE';
ALTER TABLE profiles ADD COLUMN IF NOT EXISTS last_seen_visibility VARCHAR(16) NOT NULL DEFAULT 'CONTACTS';
ALTER TABLE profiles ADD COLUMN IF NOT EXISTS last_seen_at TIMESTAMPTZ NULL;
