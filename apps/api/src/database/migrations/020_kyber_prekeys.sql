-- 020: the post-quantum half of a session's first handshake.
--
-- Current libsignal starts a session with PQXDH, which mixes a Kyber
-- encapsulation into the classical X3DH agreement. A bundle without a Kyber
-- prekey cannot start a session at all, so every device publishes one
-- alongside its signed prekey, signed by the same identity key.
--
-- Nullable because the column arrives before any device has published one;
-- the API requires it from every device that publishes keys at all.

ALTER TABLE device_identity_keys ADD COLUMN IF NOT EXISTS kyber_prekey_id INTEGER NULL;
ALTER TABLE device_identity_keys ADD COLUMN IF NOT EXISTS kyber_prekey TEXT NULL;
ALTER TABLE device_identity_keys ADD COLUMN IF NOT EXISTS kyber_prekey_signature TEXT NULL;
