-- 021: what the server needs to carry sealed files and a sealed live location.
--
-- Under end-to-end encryption a file arrives as bytes the server cannot read
-- and must not try to interpret: no mime type worth trusting, no file name, no
-- duration or waveform. All of that travels inside the encrypted message
-- instead, so the column that matters here is simply "do not touch this".
ALTER TABLE media_objects ADD COLUMN IF NOT EXISTS sealed BOOLEAN NOT NULL DEFAULT FALSE;

-- A live location is shared for a fixed time, and the server has to know when
-- that time is up in order to stop carrying updates. That is the one thing it
-- needs to know: when, never where.
ALTER TABLE messages ADD COLUMN IF NOT EXISTS live_until TIMESTAMPTZ NULL;
