-- Group chats, polls, voice-note transcripts.

-- Groups reuse conversations (is_group, title) and participants (role:
-- ADMIN | MEMBER). A description and a photo are all they add.
ALTER TABLE conversations ADD COLUMN IF NOT EXISTS description VARCHAR(300);
ALTER TABLE conversations ADD COLUMN IF NOT EXISTS avatar_media_id UUID;

-- One row per chosen option; a single-choice poll has at most one per person.
CREATE TABLE IF NOT EXISTS poll_votes (
    message_id UUID NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    option_index SMALLINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (message_id, user_id, option_index)
);

-- A voice note is transcribed once, on request, by the self-hosted speech
-- model; everyone in the conversation then sees the same text.
ALTER TABLE media_objects ADD COLUMN IF NOT EXISTS transcript TEXT;
ALTER TABLE media_objects ADD COLUMN IF NOT EXISTS transcript_lang VARCHAR(12);
ALTER TABLE media_objects ADD COLUMN IF NOT EXISTS transcribed_at TIMESTAMPTZ;

-- Search walks one conversation list at a time; this keeps it off a full scan.
CREATE INDEX IF NOT EXISTS idx_messages_conv_created_desc ON messages(conversation_id, created_at DESC);
