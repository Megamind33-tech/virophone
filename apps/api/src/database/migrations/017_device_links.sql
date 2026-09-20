-- 017: linking another device (the web companion).
--
-- The browser asks for a link, shows a short code, and waits. The phone —
-- already signed in — types that code to approve. Only then are tokens minted,
-- and they are handed over exactly once, to whoever proved they started the
-- request with the secret.
--
-- Rows are short-lived: a request expires in minutes, and the tokens column is
-- emptied the moment it is collected.

CREATE TABLE IF NOT EXISTS device_link_requests (
    id UUID PRIMARY KEY,
    code VARCHAR(16) NOT NULL UNIQUE,
    secret_hash VARCHAR(64) NOT NULL,
    user_id UUID NULL,
    device_id UUID NULL,
    platform VARCHAR(20) NOT NULL DEFAULT 'WEB',
    label VARCHAR(80) NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ NOT NULL,
    approved_at TIMESTAMPTZ NULL,
    claimed_at TIMESTAMPTZ NULL,
    tokens JSONB NULL
);

CREATE INDEX IF NOT EXISTS idx_device_link_requests_expires ON device_link_requests (expires_at);
