-- 024: Loop answers the server carries without reading.
--
-- A Loop's promise is that neither of you sees the other's answer until you
-- have both answered, and the server is what makes that true — it holds the
-- answers and decides when to hand them over. That does not have to mean it
-- can read them.
--
-- So an answer is sealed per device, exactly like a message, and the server
-- goes on withholding it until the moment it would have revealed the words.
-- The reveal rule is unchanged; what changes is that the thing being withheld
-- is ciphertext, and stays ciphertext afterwards.
CREATE TABLE IF NOT EXISTS loop_answer_envelopes (
    answer_id UUID NOT NULL REFERENCES loop_answers(id) ON DELETE CASCADE,
    device_id UUID NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    ciphertext TEXT NOT NULL,
    envelope_type SMALLINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (answer_id, device_id)
);

CREATE INDEX IF NOT EXISTS idx_loop_answer_envelopes_user ON loop_answer_envelopes (user_id);

-- An answer with no readable text is a sealed one; the words are in the
-- envelopes above.
ALTER TABLE loop_answers ADD COLUMN IF NOT EXISTS sealed BOOLEAN NOT NULL DEFAULT FALSE;

-- Which device sealed an answer, so the reader can find the session it
-- belongs to — the same thing a sealed message carries.
ALTER TABLE loop_answers ADD COLUMN IF NOT EXISTS device_id UUID NULL;
