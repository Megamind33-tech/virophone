-- 028: why people came together.
--
-- A Moment used to say what someone was up to (free, listening, gaming). The
-- room engine starts from what people want to do *together* — cook, watch,
-- listen, just stay — and opens the room shaped for that. The old type stays,
-- because the Now feed still reads it; a Moment made before this column
-- existed opens into a room derived from its type.
--
-- What the room is *right now* is not stored here: it changes constantly and
-- only matters while the Moment lives, so it is held in Redis.
ALTER TABLE moments ADD COLUMN IF NOT EXISTS intent varchar(16) NULL
  CHECK (intent IS NULL OR intent IN (
    'BE','TALK','WATCH','LISTEN','PLAY','COOK','WALK','CHOOSE','LEARN','CELEBRATE','REMEMBER','STAY'
  ));
