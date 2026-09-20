-- 015: @mentions in groups, and group invite links.
--
-- invite_code: the shareable code for a group. Nullable, because a group has
--   no link until an admin makes one, and revoking sets it back to NULL.
--   Resetting the link writes a new code, which retires the old one.
-- The index on messages.metadata serves "did anyone mention me?" for the
--   inbox badge; jsonb_path_ops keeps it small and fast for containment.

ALTER TABLE conversations ADD COLUMN IF NOT EXISTS invite_code VARCHAR(32) NULL;
ALTER TABLE conversations ADD COLUMN IF NOT EXISTS invite_created_at TIMESTAMPTZ NULL;
ALTER TABLE conversations ADD COLUMN IF NOT EXISTS invite_created_by UUID NULL;

CREATE UNIQUE INDEX IF NOT EXISTS idx_conversations_invite_code ON conversations (invite_code) WHERE invite_code IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_messages_metadata_mentions ON messages USING GIN (metadata jsonb_path_ops);
