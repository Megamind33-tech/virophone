-- 039: intimate signals — a presence event, not a message.
--
-- A signal is somebody reaching for someone without a call, a text or a
-- reaction: "Thinking of you", "I miss you", "I'm here". It is not chat
-- content, is never displayed as a message, and carries no words of its own.
-- The server's whole job is to count them privately, keep them inside a
-- real connection, and wake the other phone once.
--
-- Repeats are part of the language: five quick taps are one stronger signal,
-- not five interruptions. Rows aggregate within a short window (count), and
-- the day's total is what the recipient ever sees — never a public counter.

CREATE TABLE IF NOT EXISTS intimate_signals (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  sender_user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  recipient_user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  kind varchar(24) NOT NULL CHECK (kind IN ('THINKING_OF_YOU','MISS_YOU','HERE','NEED_YOUR_VOICE','PROUD','MADE_ME_SMILE','HOLD_ME','CHECKING_ON_YOU')),
  -- How many taps this aggregated signal stands for.
  count int NOT NULL DEFAULT 1,
  first_sent_at timestamptz NOT NULL DEFAULT now(),
  sent_at timestamptz NOT NULL DEFAULT now(),
  -- Both people reached for each other close enough together to say so.
  mutual boolean NOT NULL DEFAULT false,
  -- PUSHED → RECEIVED → PRESENTED → OPENED/RESPONDED. Technical states for
  -- delivery; the product never shows them to anyone.
  state varchar(16) NOT NULL DEFAULT 'PUSHED',
  responded_kind varchar(24) NULL,
  responded_at timestamptz NULL
);

CREATE INDEX IF NOT EXISTS intimate_signals_pair
  ON intimate_signals (recipient_user_id, sender_user_id, kind, sent_at DESC);
CREATE INDEX IF NOT EXISTS intimate_signals_recipient
  ON intimate_signals (recipient_user_id, state);
