-- 023: messages that are not messages.
--
-- A reaction is content — which emoji someone chose says something — so under
-- end-to-end encryption it cannot be a row in the reactions table for the
-- server to read. It becomes a sealed message like any other, and the phones
-- apply it to the message it belongs to.
--
-- The one thing the server needs to know about such a message is that it is
-- not one: no notification, and no unread badge for a thumbs up.
ALTER TABLE messages ADD COLUMN IF NOT EXISTS silent BOOLEAN NOT NULL DEFAULT FALSE;
