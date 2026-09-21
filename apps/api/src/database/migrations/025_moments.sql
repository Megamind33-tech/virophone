CREATE TABLE moments (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  creator_user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  type varchar(16) NOT NULL CHECK (type IN ('FREE','BREAK','LISTENING','WATCHING','GAMING','WORKING','CUSTOM')),
  text varchar(60),
  visibility varchar(16) NOT NULL CHECK (visibility IN ('CONNECTIONS','CONTACTS')),
  created_at timestamptz NOT NULL DEFAULT now(),
  expires_at timestamptz NOT NULL,
  status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','ENDED','EXPIRED')),
  CHECK (expires_at > created_at),
  CHECK (type <> 'CUSTOM' OR length(trim(text)) > 0)
);
CREATE UNIQUE INDEX moments_one_active_per_creator ON moments(creator_user_id) WHERE status = 'ACTIVE';
CREATE INDEX moments_expiration ON moments(expires_at) WHERE status = 'ACTIVE';
