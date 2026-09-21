-- 022: somewhere to keep an encrypted backup of someone's chats.
--
-- An end-to-end encrypted message can be opened once, by the device it was
-- addressed to. That means the plaintext on a phone is the only copy there
-- will ever be, and a reinstall loses every conversation. This is the fix: the
-- phone encrypts its own message store with a recovery key the person keeps,
-- and the server holds the result without being able to open it.
--
-- One backup per person, replaced each time. The row records only what is
-- needed to show "last backed up, 2 hours ago, 1,284 messages" — never what is
-- in it.
CREATE TABLE IF NOT EXISTS message_backups (
    user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    file_name VARCHAR(80) NOT NULL,
    size_bytes BIGINT NOT NULL,
    message_count INTEGER NOT NULL DEFAULT 0,
    conversation_count INTEGER NOT NULL DEFAULT 0,
    /** Which device wrote it, so the person can see where their backup came from. */
    device_id UUID NULL,
    /** The format the phone wrote, so a later one can be recognised. */
    version INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
