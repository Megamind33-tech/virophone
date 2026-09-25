-- A phone that cannot open a sealed message asks its author's phone to seal it
-- again. The request used to exist only as a live frame, which never reached
-- an author who was offline — and on a phone shared by two accounts, the
-- author is by definition signed out while the other one asks. Kept here until
-- the author's phone answers, so it is collected at the author's next sign-in.
--
-- Identifiers only: which message, which device wants it. The server still
-- never holds anything it could read.
CREATE TABLE IF NOT EXISTS message_resend_requests (
  message_id uuid NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
  requester_device_id uuid NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  requester_user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  author_user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (message_id, requester_device_id)
);
CREATE INDEX IF NOT EXISTS message_resend_requests_author ON message_resend_requests(author_user_id, created_at);
