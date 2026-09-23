-- Replying to a remark in a Moment.
--
-- Until now a message could not point at another one, so answering somebody in
-- a room meant repeating them or hoping the order made it obvious. In a room
-- where several people are talking at once, neither works.
--
-- Only the link lives here. What the reply is answering is resolved by the
-- client from the messages it already holds, which matters in a sealed room:
-- the server cannot read a sealed body, so it must not be the thing that
-- carries a preview of one. A link is safe because it says nothing.
ALTER TABLE moment_messages
  ADD COLUMN IF NOT EXISTS reply_to_id uuid
  REFERENCES moment_messages(id) ON DELETE SET NULL;

-- Answers are looked up by what they answer when a room's backlog is read.
CREATE INDEX IF NOT EXISTS moment_messages_reply_to_idx
  ON moment_messages (reply_to_id) WHERE reply_to_id IS NOT NULL;
