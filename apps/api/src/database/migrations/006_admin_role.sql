-- Admin role for moderation APIs (E4)
ALTER TABLE users
  ADD COLUMN IF NOT EXISTS admin_role VARCHAR(20) NOT NULL DEFAULT 'USER';
