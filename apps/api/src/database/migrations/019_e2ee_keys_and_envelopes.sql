-- 019: the foundations for end-to-end encrypted messages.
--
-- Two halves:
--
-- 1. A key directory. Each device publishes an identity key, a signed prekey
--    and a batch of one-time prekeys. The server hands these out so one device
--    can start a session with another. It never sees a private key, and cannot
--    read anything sent with them.
--
-- 2. Envelopes. An encrypted message is stored once per recipient device: the
--    server keeps ciphertext it cannot open, and each device collects its own
--    copy. A device that was not around when a message was sent has no
--    envelope for it — which is why history does not follow you to a new phone
--    until encrypted backup exists.

CREATE TABLE IF NOT EXISTS device_identity_keys (
    device_id UUID PRIMARY KEY REFERENCES devices(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    registration_id INTEGER NOT NULL,
    identity_key TEXT NOT NULL,
    signed_prekey_id INTEGER NOT NULL,
    signed_prekey TEXT NOT NULL,
    signed_prekey_signature TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_device_identity_keys_user ON device_identity_keys (user_id);

-- Handed out one at a time and never reused: that is what makes the first
-- message to an offline device safe.
CREATE TABLE IF NOT EXISTS device_one_time_prekeys (
    id BIGSERIAL PRIMARY KEY,
    device_id UUID NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    key_id INTEGER NOT NULL,
    public_key TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    consumed_at TIMESTAMPTZ NULL,
    UNIQUE (device_id, key_id)
);

CREATE INDEX IF NOT EXISTS idx_device_one_time_prekeys_free
    ON device_one_time_prekeys (device_id) WHERE consumed_at IS NULL;

-- Every envelope dies with its message, and with the device it was addressed
-- to: there is no path that leaves ciphertext behind with nothing to open it.
CREATE TABLE IF NOT EXISTS message_envelopes (
    message_id UUID NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    device_id UUID NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    ciphertext TEXT NOT NULL,
    envelope_type SMALLINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (message_id, device_id)
);

CREATE INDEX IF NOT EXISTS idx_message_envelopes_device ON message_envelopes (device_id, created_at DESC);

-- Marked once the first encrypted message arrives, so every device of both
-- people knows this conversation is encrypted.
ALTER TABLE conversations ADD COLUMN IF NOT EXISTS encrypted BOOLEAN NOT NULL DEFAULT FALSE;
