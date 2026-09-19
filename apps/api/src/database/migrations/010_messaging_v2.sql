-- Messaging v2: edits, deletes, replies, reactions, voice media, disappearing
-- and scheduled messages, private sessions, and per-person chat settings.

-- Every change to a message bumps updated_at, which is what devices sync on:
-- "give me everything that changed since my cursor" covers new, edited,
-- deleted and reacted-to messages with one query, so a device that missed a
-- live frame (socket blip, phone asleep) catches up exactly.
ALTER TABLE messages ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW();
ALTER TABLE messages ADD COLUMN IF NOT EXISTS reply_to_id UUID;
ALTER TABLE messages ADD COLUMN IF NOT EXISTS edited_at TIMESTAMPTZ;
-- Delete-for-everyone keeps a tombstone row (body cleared) so both sides show
-- "This message was deleted" rather than silently rewriting history.
ALTER TABLE messages ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ;
-- Disappearing / private-session messages: hard-deleted by the sweeper.
ALTER TABLE messages ADD COLUMN IF NOT EXISTS expires_at TIMESTAMPTZ;
ALTER TABLE messages ADD COLUMN IF NOT EXISTS view_once BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE messages ADD COLUMN IF NOT EXISTS media_id UUID;
ALTER TABLE messages ADD COLUMN IF NOT EXISTS forwarded BOOLEAN NOT NULL DEFAULT FALSE;
-- Send later: held back from recipients until the sweeper releases it.
ALTER TABLE messages ADD COLUMN IF NOT EXISTS deliver_at TIMESTAMPTZ;
-- Small structured extras (bubble effect, loop reference, system event kind).
ALTER TABLE messages ADD COLUMN IF NOT EXISTS metadata JSONB;

CREATE INDEX IF NOT EXISTS idx_messages_updated ON messages(conversation_id, updated_at);
CREATE INDEX IF NOT EXISTS idx_messages_expires ON messages(expires_at) WHERE expires_at IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_messages_deliver ON messages(deliver_at) WHERE deliver_at IS NOT NULL;

-- One reaction per person per message, as in WhatsApp: reacting again replaces it.
CREATE TABLE IF NOT EXISTS message_reactions (
    message_id UUID NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    emoji VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (message_id, user_id)
);

-- Delete for me.
CREATE TABLE IF NOT EXISTS message_hidden (
    message_id UUID NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (message_id, user_id)
);

-- View-once: who has opened it.
CREATE TABLE IF NOT EXISTS message_views (
    message_id UUID NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    viewed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (message_id, user_id)
);

-- Starred is personal; pinned is shared by the conversation.
CREATE TABLE IF NOT EXISTS message_stars (
    message_id UUID NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (message_id, user_id)
);
CREATE TABLE IF NOT EXISTS conversation_pins (
    conversation_id UUID NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    message_id UUID NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    pinned_by UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (conversation_id, message_id)
);

-- Uploaded media (voice notes). Never public: served only to participants of
-- a conversation holding a message that references it.
CREATE TABLE IF NOT EXISTS media_objects (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    kind VARCHAR(16) NOT NULL,
    mime VARCHAR(64) NOT NULL,
    size_bytes INTEGER NOT NULL,
    duration_ms INTEGER,
    waveform TEXT,
    file_name VARCHAR(80) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Conversation kinds: DM (the one permanent 1:1 thread) and PRIVATE (a
-- temporary session that is deleted outright at expires_at).
ALTER TABLE conversations ADD COLUMN IF NOT EXISTS kind VARCHAR(16) NOT NULL DEFAULT 'DM';
ALTER TABLE conversations ADD COLUMN IF NOT EXISTS expires_at TIMESTAMPTZ;
-- Shared disappearing-messages timer, applied to messages sent after it is set.
ALTER TABLE conversations ADD COLUMN IF NOT EXISTS disappearing_seconds INTEGER;
-- Set by Reset: everything before it is gone for both people.
ALTER TABLE conversations ADD COLUMN IF NOT EXISTS reset_at TIMESTAMPTZ;
CREATE INDEX IF NOT EXISTS idx_conversations_expires ON conversations(expires_at) WHERE expires_at IS NOT NULL;

-- Per-person settings.
ALTER TABLE conversation_participants ADD COLUMN IF NOT EXISTS hidden BOOLEAN NOT NULL DEFAULT FALSE;
-- Delete chat (for me): messages up to this instant are gone for this person only.
ALTER TABLE conversation_participants ADD COLUMN IF NOT EXISTS cleared_at TIMESTAMPTZ;
ALTER TABLE conversation_participants ADD COLUMN IF NOT EXISTS muted_until TIMESTAMPTZ;
ALTER TABLE conversation_participants ADD COLUMN IF NOT EXISTS last_delivered_at TIMESTAMPTZ;
