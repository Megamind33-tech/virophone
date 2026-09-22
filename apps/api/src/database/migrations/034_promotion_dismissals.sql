-- 034: a promotion somebody has waved away.
--
-- Dismissal belongs on the server, not on the phone. Something told once and
-- then dismissed should stay dismissed — on the next phone, after a reinstall,
-- and on the tablet — because a promotion that comes back is the behaviour
-- people hate most about being marketed at.
--
-- Rows cascade with the promotion, so clearing a campaign clears its
-- dismissals too and nothing is kept about a person for a thing that no longer
-- exists.
CREATE TABLE IF NOT EXISTS promotion_dismissals (
  promotion_id uuid NOT NULL REFERENCES promotions(id) ON DELETE CASCADE,
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  dismissed_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (promotion_id, user_id)
);
CREATE INDEX IF NOT EXISTS promotion_dismissals_person ON promotion_dismissals (user_id);
