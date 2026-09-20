-- 014: document attachments, shared contact cards, and inbox tools.
--
-- original_name: a document is sent under the name the sender saw
--   ("Invoice March.pdf"); media files themselves are stored under a random id.
-- archived_at / pinned_at: per person, not per conversation — archiving a chat
--   is my choice, not something the other side sees.
-- unread_marked: "mark as unread" survives until the chat is opened again.

ALTER TABLE media_objects ADD COLUMN IF NOT EXISTS original_name VARCHAR(255) NULL;

ALTER TABLE conversation_participants ADD COLUMN IF NOT EXISTS archived_at TIMESTAMPTZ NULL;
ALTER TABLE conversation_participants ADD COLUMN IF NOT EXISTS pinned_at TIMESTAMPTZ NULL;
ALTER TABLE conversation_participants ADD COLUMN IF NOT EXISTS unread_marked BOOLEAN NOT NULL DEFAULT FALSE;
