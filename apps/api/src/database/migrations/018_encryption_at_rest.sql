-- 018: encryption at rest for message content.
--
-- messages.body and messages.metadata are written encrypted from now on. Rows
-- written before are left alone and still read back as they are, so the
-- database can be half-migrated without being broken; scripts/encrypt-backfill
-- converts the rest.
--
-- Encrypted metadata is an opaque string, so the one thing the server used to
-- search inside it — who was mentioned — moves into its own table. The old GIN
-- index has nothing left to do.

CREATE TABLE IF NOT EXISTS message_mentions (
    message_id UUID NOT NULL,
    user_id UUID NOT NULL,
    conversation_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (message_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_message_mentions_lookup
    ON message_mentions (user_id, conversation_id, created_at DESC);

DROP INDEX IF EXISTS idx_messages_metadata_mentions;

-- Fills in what the old metadata already recorded, so mentions made before
-- this release keep their badge.
INSERT INTO message_mentions (message_id, user_id, conversation_id, created_at)
SELECT m.id, mentioned.value::uuid, m.conversation_id, m.created_at
FROM messages m
CROSS JOIN LATERAL jsonb_array_elements_text(m.metadata -> 'mentions') AS mentioned(value)
WHERE jsonb_typeof(m.metadata -> 'mentions') = 'array'
ON CONFLICT DO NOTHING;
