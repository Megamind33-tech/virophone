-- Who somebody is, in their own words: what they love, what they are good at,
-- what they struggle with, what frightens them, what they hope for and what
-- they are working towards. Plus when they were born.
--
-- Viro knew a name, a photo and one line. That is enough to reach somebody and
-- nowhere near enough to understand them, and understanding them is what the
-- people close to them are here for.
--
-- This is the most personal thing the app holds, and the schema is written to
-- make the careful thing the easy thing:
--
--  * Every topic has its own visibility, answered by the same gate as every
--    other profile field (VisibilityService.canSee), and blocks hide it all.
--  * Fears and weaknesses can never be shown to EVERYONE. Everyone means any
--    stranger who finds the profile, and somebody's fears are exactly what a
--    stranger should not be handed. The constraint enforces it even if a
--    client, a script or a future endpoint forgets to.
--  * Nothing is required. A topic with no row was not answered, which is a
--    real answer and is shown as nothing at all.

CREATE TABLE IF NOT EXISTS profile_topics (
  user_id     uuid        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  topic       varchar(16) NOT NULL,
  entries     text[]      NOT NULL DEFAULT '{}',
  visibility  varchar(16) NOT NULL,
  updated_at  timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, topic),
  CONSTRAINT profile_topics_topic CHECK (
    topic IN ('INTERESTS', 'STRENGTHS', 'WEAKNESSES', 'FEARS', 'DREAMS', 'GOALS')
  ),
  CONSTRAINT profile_topics_visibility CHECK (
    visibility IN ('EVERYONE', 'CONTACTS', 'NOBODY')
  ),
  CONSTRAINT profile_topics_vulnerable_never_public CHECK (
    NOT (topic IN ('FEARS', 'WEAKNESSES') AND visibility = 'EVERYONE')
  ),
  CONSTRAINT profile_topics_bounded CHECK (cardinality(entries) <= 8)
);

-- Date of birth is held, never shown. At most the birthday — day and month —
-- is shared, and only if the person says so. The year is how old somebody is,
-- and that is theirs to tell.
ALTER TABLE profiles ADD COLUMN IF NOT EXISTS birth_date date NULL;
ALTER TABLE profiles ADD COLUMN IF NOT EXISTS birthday_visibility varchar(16) NOT NULL DEFAULT 'NOBODY';
ALTER TABLE profiles DROP CONSTRAINT IF EXISTS profiles_birthday_visibility;
ALTER TABLE profiles ADD CONSTRAINT profiles_birthday_visibility
  CHECK (birthday_visibility IN ('EVERYONE', 'CONTACTS', 'NOBODY'));
