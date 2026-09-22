-- 033: campaigns, and the promotions inside them.
--
-- A campaign is a named thing that runs for a period: an announcement about a
-- new feature, a seasonal offer, a note to people on a particular plan. The
-- promotions are what it actually says, in the order somebody arranged them.
--
-- Liveness is not stored. A row saying LIVE is a row that goes on saying LIVE
-- after the campaign has finished, and every screen that reads it inherits the
-- lie. Status records intent — is this a draft, is it meant to run, has it
-- been put away — and whether it is running right now is derived from the
-- clock every time it is asked. The same reasoning as the admin overview
-- counting rows rather than keeping totals.
CREATE TABLE IF NOT EXISTS campaigns (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  name varchar(80) NOT NULL CHECK (length(trim(name)) > 0),
  -- DRAFT: being written. SCHEDULED: meant to run, and does so inside its
  -- period. ARCHIVED: kept for the record, never shown again.
  status varchar(12) NOT NULL DEFAULT 'DRAFT'
    CHECK (status IN ('DRAFT', 'SCHEDULED', 'ARCHIVED')),
  -- Who it is for. Deliberately coarse: finer targeting is a product decision
  -- about what may be inferred from somebody's account, not a schema detail.
  audience varchar(16) NOT NULL DEFAULT 'EVERYONE'
    CHECK (audience IN ('EVERYONE', 'SUBSCRIBERS', 'FREE')),
  starts_at timestamptz,
  ends_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  -- A period that ends before it starts is a mistake worth refusing at the
  -- door rather than rendering as a campaign that can never run.
  CHECK (ends_at IS NULL OR starts_at IS NULL OR ends_at > starts_at)
);
CREATE INDEX IF NOT EXISTS campaigns_running ON campaigns (status, starts_at, ends_at);

-- What a campaign says, in the order somebody put them in.
--
-- position is a plain integer rewritten as a block whenever the order changes,
-- rather than fractional ranks that drift: these lists are a handful of items
-- arranged by hand, and rewriting five rows is cheaper than explaining why two
-- promotions ended up with the same rank.
CREATE TABLE IF NOT EXISTS promotions (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  campaign_id uuid NOT NULL REFERENCES campaigns(id) ON DELETE CASCADE,
  title varchar(80) NOT NULL CHECK (length(trim(title)) > 0),
  body varchar(240),
  -- Where tapping it goes. A Viro route, not an arbitrary address: a promotion
  -- that can send somebody anywhere is a phishing surface with an admin login
  -- in front of it.
  action varchar(120),
  position integer NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS promotions_order ON promotions (campaign_id, position);
