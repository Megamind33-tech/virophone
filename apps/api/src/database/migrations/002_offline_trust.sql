-- Phase 1A: offline trust material sync tracking
-- Tokens are derived server-side; this table tracks issuance epochs for revocation.

CREATE TABLE IF NOT EXISTS offline_trust_epochs (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    peer_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    epoch BIGINT NOT NULL DEFAULT 1,
    issued_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (user_id, peer_user_id)
);

CREATE INDEX IF NOT EXISTS idx_offline_trust_expires ON offline_trust_epochs(expires_at);
