-- Phase 2 Viro Now: the room attached to a Moment. Everything here is
-- deliberately separate from permanent conversations — a room dies with its
-- Moment (participants are rows, messages are rows, and both are purged when
-- the Moment ends or expires), so nothing can leak into Chats history.
CREATE TABLE moment_participants (
  moment_id uuid NOT NULL REFERENCES moments(id) ON DELETE CASCADE,
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  joined_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (moment_id, user_id)
);

CREATE TABLE moment_messages (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  moment_id uuid NOT NULL REFERENCES moments(id) ON DELETE CASCADE,
  sender_user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  body varchar(500) NOT NULL CHECK (length(trim(body)) > 0),
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX moment_messages_room ON moment_messages(moment_id, created_at);

-- One live reaction per user per message; re-reacting switches the emoji and
-- sending null removes it. The emoji set is enforced in the service so the
-- allowed set lives in one place with the API contract.
CREATE TABLE moment_reactions (
  message_id uuid NOT NULL REFERENCES moment_messages(id) ON DELETE CASCADE,
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  emoji varchar(16) NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (message_id, user_id)
);

-- A knock is voice intent, not a call: ACCEPTED only records that the host
-- acted on it (the call itself is the existing call flow). Rows cascade with
-- the Moment.
CREATE TABLE moment_knocks (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  moment_id uuid NOT NULL REFERENCES moments(id) ON DELETE CASCADE,
  knocker_user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  status varchar(16) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','ACCEPTED','DISMISSED')),
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (moment_id, knocker_user_id)
);

CREATE TABLE moment_invitations (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  moment_id uuid NOT NULL REFERENCES moments(id) ON DELETE CASCADE,
  invitee_user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (moment_id, invitee_user_id)
);
