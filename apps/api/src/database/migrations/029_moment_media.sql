-- 029: things people share to watch or listen to together in a Moment.
--
-- Only media a participant brings themselves: a video or a song on their own
-- phone, which they have the right to share. There is no catalogue here and
-- no connection to any streaming service.
--
-- The file lives only as long as the Moment, and only while the person who
-- shared it is still in the room: leaving takes it with them, and the Moment
-- ending removes everything. Each file is encrypted on disk with its own key,
-- held here (itself encrypted at rest when MESSAGE_ENCRYPTION_KEY is set), so
-- a copied disk gives nothing away.
--
-- Where playback is — playing, paused, at which second — is not stored here:
-- it changes constantly and matters only while the Moment lives, so it is
-- held in Redis with the rest of the room.
CREATE TABLE IF NOT EXISTS moment_media (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  moment_id uuid NOT NULL REFERENCES moments(id) ON DELETE CASCADE,
  owner_user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  provider varchar(16) NOT NULL DEFAULT 'UPLOAD' CHECK (provider IN ('UPLOAD')),
  kind varchar(8) NOT NULL CHECK (kind IN ('VIDEO', 'AUDIO')),
  mime varchar(64) NOT NULL,
  title varchar(120) NOT NULL CHECK (length(trim(title)) > 0),
  size_bytes bigint NOT NULL CHECK (size_bytes > 0),
  duration_ms integer NULL CHECK (duration_ms IS NULL OR duration_ms >= 0),
  file_name varchar(64) NOT NULL,
  file_key text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS moment_media_room ON moment_media(moment_id, created_at);
