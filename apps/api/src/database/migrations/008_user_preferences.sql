-- Preferences that used to live only on the handset.
--
-- Favourites, custom contact names and hidden contacts had no server
-- representation at all, so they were destroyed by a reinstall, a new phone or
-- a lost one — unlike calls, messages and contacts, which already follow the
-- account because they are rows in this database. This closes that gap without
-- a backup/restore mechanism: the preferences simply belong to the account.
--
-- Contact preferences are keyed by E.164 phone number rather than by the
-- device's own contact id, which is meaningless on a different handset, and
-- rather than by matched Viro user id, which is null for contacts who have not
-- joined yet — precisely the ones a user is most likely to have renamed.

CREATE TABLE IF NOT EXISTS contact_preferences (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    phone_e164 VARCHAR(20) NOT NULL,
    is_favorite BOOLEAN NOT NULL DEFAULT FALSE,
    custom_display_name VARCHAR(120),
    is_hidden BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (user_id, phone_e164)
);
CREATE INDEX IF NOT EXISTS idx_contact_preferences_user ON contact_preferences(user_id);

-- Appearance travels too, so a replacement phone looks like the old one.
-- Wallpaper is intentionally absent: it is stored as a device-local content URI
-- that means nothing on another handset.
CREATE TABLE IF NOT EXISTS user_app_preferences (
    user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    theme_mode VARCHAR(10) NOT NULL DEFAULT 'SYSTEM',
    font_size VARCHAR(10) NOT NULL DEFAULT 'STANDARD',
    density VARCHAR(12) NOT NULL DEFAULT 'COMFORTABLE',
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
