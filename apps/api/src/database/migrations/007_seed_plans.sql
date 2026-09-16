-- Seed default Free / Plus plans (idempotent by name).
INSERT INTO plans (id, name, description, is_active)
SELECT gen_random_uuid(), 'Free', 'Core calling and messaging for everyone on Viro.', TRUE
WHERE NOT EXISTS (SELECT 1 FROM plans WHERE name = 'Free');

INSERT INTO plans (id, name, description, is_active)
SELECT gen_random_uuid(), 'Plus', 'Preview Plus plan (billing integration comes later).', TRUE
WHERE NOT EXISTS (SELECT 1 FROM plans WHERE name = 'Plus');
