-- 013: finish-your-profile + reaching people without a phone number.
--
-- profile_completed_at: set once the person has chosen their name and Viro ID.
--   NULL means the app shows the one-time "Your name" step after sign-in
--   (phone sign-ups used to be created with an empty name, email sign-ups with
--   the email's local part).
-- discoverable_by_email: whether an exact, verified email match may find this
--   person in "Find people". On by default so email-only accounts are
--   reachable; the person can switch it off.

ALTER TABLE profiles ADD COLUMN IF NOT EXISTS profile_completed_at TIMESTAMPTZ NULL;
ALTER TABLE profiles ADD COLUMN IF NOT EXISTS discoverable_by_email BOOLEAN NOT NULL DEFAULT TRUE;

-- Reverse lookups for connection requests addressed to a user.
CREATE INDEX IF NOT EXISTS idx_viro_connections_recipient ON viro_connections (recipient_user_id, status);
