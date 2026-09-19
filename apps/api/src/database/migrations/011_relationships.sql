-- Relationship intelligence: Loops (shared), and Targets, Moments,
-- Commitments and Achievements (private to their owner).
--
-- Privacy is structural, not a flag: every private table is keyed by
-- owner_user_id and is only ever read with it. Nothing here is joined into
-- anything another user can fetch. Only Loops are shared, because both people
-- deliberately take part in them.

-- A person the owner cares about. subject_phone is preferred (it survives the
-- person joining Viro later); subject_user_id is filled when known.
CREATE TABLE IF NOT EXISTS relationships (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    subject_key VARCHAR(80) NOT NULL,
    subject_user_id UUID,
    subject_phone VARCHAR(20),
    display_name VARCHAR(120),
    -- PERSONAL | PROFESSIONAL
    category VARCHAR(16) NOT NULL DEFAULT 'PERSONAL',
    -- PARTNER, FAMILY, PARENT, CHILD, FRIEND, BEST_FRIEND, RELATIVE, CLIENT,
    -- CUSTOMER, EMPLOYEE, MANAGER, BUSINESS_PARTNER, SUPPLIER, PROSPECT,
    -- COLLEAGUE, CUSTOM
    relationship_type VARCHAR(24) NOT NULL DEFAULT 'FRIEND',
    custom_label VARCHAR(40),
    -- Chat look: CLOSE | FAMILY | FRIENDS | WORK (null = follow the type)
    vibe VARCHAR(16),
    -- Target: DAILY | WEEKLY | MONTHLY | EVERY_N_DAYS | WEEKDAY (null = none)
    target_cadence VARCHAR(16),
    target_count SMALLINT NOT NULL DEFAULT 1,
    target_every_days SMALLINT,
    target_weekday SMALLINT,
    target_label VARCHAR(80),
    reminders_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    notes TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (owner_user_id, subject_key)
);
CREATE INDEX IF NOT EXISTS idx_relationships_owner ON relationships(owner_user_id);

CREATE TABLE IF NOT EXISTS important_dates (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    relationship_id UUID NOT NULL REFERENCES relationships(id) ON DELETE CASCADE,
    -- BIRTHDAY, ANNIVERSARY, FIRST_MEETING, WEDDING_ANNIVERSARY, GRADUATION,
    -- CHILD_BIRTHDAY, CONTRACT_RENEWAL, PAYMENT, FOLLOW_UP, BUSINESS_REVIEW, CUSTOM
    kind VARCHAR(24) NOT NULL,
    label VARCHAR(80),
    month SMALLINT NOT NULL CHECK (month BETWEEN 1 AND 12),
    day SMALLINT NOT NULL CHECK (day BETWEEN 1 AND 31),
    -- Set for one-off dates (a contract renewal); null repeats every year.
    year SMALLINT,
    remind_days_before SMALLINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_dates_owner ON important_dates(owner_user_id);

-- Something the owner said they would do. Only ever created with the owner's
-- confirmation; Viro may suggest one, never invent one.
CREATE TABLE IF NOT EXISTS commitments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    relationship_id UUID REFERENCES relationships(id) ON DELETE SET NULL,
    subject_user_id UUID,
    conversation_id UUID,
    message_id UUID,
    -- CALL | SEND | MEET | FOLLOW_UP | OTHER
    kind VARCHAR(16) NOT NULL DEFAULT 'OTHER',
    text VARCHAR(280) NOT NULL,
    due_at TIMESTAMPTZ NOT NULL,
    -- OPEN | DONE | DISMISSED
    status VARCHAR(12) NOT NULL DEFAULT 'OPEN',
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_commitments_owner ON commitments(owner_user_id, status, due_at);

-- Loops: a recurring private interaction inside a conversation. Shared.
CREATE TABLE IF NOT EXISTS loops (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id UUID NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    created_by UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title VARCHAR(80) NOT NULL,
    prompt VARCHAR(280) NOT NULL,
    -- DAILY | WEEKDAYS | WEEKLY | MONTHLY | CUSTOM | ONCE
    frequency VARCHAR(12) NOT NULL,
    -- CUSTOM: bit per weekday, Sunday = 1. WEEKLY: the weekday used.
    days_mask SMALLINT,
    -- Local time the Loop opens each period, e.g. '19:00'.
    time_of_day VARCHAR(5) NOT NULL DEFAULT '19:00',
    timezone VARCHAR(64) NOT NULL DEFAULT 'Africa/Lusaka',
    -- ANY | TEXT | VOICE | PHOTO | EMOJI | CHOICE
    response_kind VARCHAR(8) NOT NULL DEFAULT 'ANY',
    choices JSONB,
    reciprocal BOOLEAN NOT NULL DEFAULT TRUE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_loops_conversation ON loops(conversation_id);

CREATE TABLE IF NOT EXISTS loop_answers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    loop_id UUID NOT NULL REFERENCES loops(id) ON DELETE CASCADE,
    -- Which occurrence: '2026-09-19' (daily), '2026-W38' (weekly),
    -- '2026-09' (monthly), 'once'.
    period_key VARCHAR(16) NOT NULL,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    -- TEXT | VOICE | PHOTO | EMOJI | CHOICE
    kind VARCHAR(8) NOT NULL,
    text VARCHAR(1000),
    media_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (loop_id, period_key, user_id)
);
CREATE INDEX IF NOT EXISTS idx_loop_answers_loop ON loop_answers(loop_id, period_key);

-- Achievements already earned, so each is announced once.
CREATE TABLE IF NOT EXISTS user_achievements (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    achievement_key VARCHAR(80) NOT NULL,
    title VARCHAR(80) NOT NULL,
    detail VARCHAR(200) NOT NULL,
    unlocked_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    shared BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (user_id, achievement_key)
);

-- Notification preferences for relationship reminders.
CREATE TABLE IF NOT EXISTS relationship_settings (
    user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    timezone VARCHAR(64) NOT NULL DEFAULT 'Africa/Lusaka',
    quiet_start VARCHAR(5) NOT NULL DEFAULT '21:30',
    quiet_end VARCHAR(5) NOT NULL DEFAULT '07:00',
    brief_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    brief_time VARCHAR(5) NOT NULL DEFAULT '08:00',
    -- LOW | NORMAL | HIGH: how many nudges a day at most (1 / 2 / 4).
    frequency VARCHAR(8) NOT NULL DEFAULT 'NORMAL',
    personal_reminders BOOLEAN NOT NULL DEFAULT TRUE,
    professional_reminders BOOLEAN NOT NULL DEFAULT TRUE,
    date_reminders BOOLEAN NOT NULL DEFAULT TRUE,
    loop_notifications BOOLEAN NOT NULL DEFAULT TRUE,
    achievement_notifications BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Check-ins Viro cannot see for itself: a normal phone call, a visit. Logged
-- by the owner with one tap so their targets reflect real life.
CREATE TABLE IF NOT EXISTS relationship_checkins (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    relationship_id UUID NOT NULL REFERENCES relationships(id) ON DELETE CASCADE,
    at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    note VARCHAR(120)
);
CREATE INDEX IF NOT EXISTS idx_checkins_rel ON relationship_checkins(relationship_id, at);

-- Photos in chat and in Loop answers share the media table.
ALTER TABLE media_objects ADD COLUMN IF NOT EXISTS width INTEGER;
ALTER TABLE media_objects ADD COLUMN IF NOT EXISTS height INTEGER;
