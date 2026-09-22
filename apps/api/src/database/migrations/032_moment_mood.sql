-- 032: how the person is, not just what they are doing.
--
-- A Moment already says the activity and, since 031, what the host said. It
-- does not say how they are, and that is often the thing worth knowing before
-- you walk in: cooking while happy and cooking while flat are different rooms
-- to step into, and somebody deciding whether to join deserves to know which
-- one they are joining.
--
-- Four, because four is what a person can answer without thinking, and the
-- same four the mood artwork draws. Optional: plenty of Moments are just a
-- Moment, and a required mood would turn opening a door into a form.
ALTER TABLE moments ADD COLUMN IF NOT EXISTS mood varchar(8)
  CHECK (mood IS NULL OR mood IN ('HAPPY', 'SAD', 'ANGRY', 'CRAZY'));
