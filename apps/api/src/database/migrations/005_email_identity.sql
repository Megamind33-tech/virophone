-- Email-based OTP login (cheaper than SMS). Adds an email identity and lets the
-- shared OTP challenge machinery carry either a phone or an email.

ALTER TABLE otp_challenges ALTER COLUMN phone_e164 DROP NOT NULL;
ALTER TABLE otp_challenges ADD COLUMN IF NOT EXISTS email VARCHAR(255);
ALTER TABLE otp_challenges ADD COLUMN IF NOT EXISTS channel VARCHAR(10) NOT NULL DEFAULT 'sms';

CREATE TABLE IF NOT EXISTS email_identities (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    email VARCHAR(255) NOT NULL,
    verified_at TIMESTAMPTZ,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (email)
);
CREATE INDEX IF NOT EXISTS idx_email_identities_user ON email_identities(user_id);
