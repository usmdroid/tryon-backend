-- V15: developer sandbox keys (dev_ prefix, no wallet involvement)

CREATE TABLE dev_sandbox_keys (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    dev_key      VARCHAR(64)  NOT NULL UNIQUE,  -- format: dev_ + 20 url-safe chars
    created_by   UUID         NULL REFERENCES clients(id),
    used_count   INT          NOT NULL DEFAULT 0,
    max_count    INT          NOT NULL DEFAULT 20,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_used_at TIMESTAMPTZ  NULL,
    revoked_at   TIMESTAMPTZ  NULL
);

CREATE INDEX idx_dev_sandbox_keys_created_by ON dev_sandbox_keys (created_by);

-- Relax tryon_events.partner_id: dev sandbox events store devKeyId which is not a clients FK
ALTER TABLE tryon_events ALTER COLUMN partner_id DROP NOT NULL;
ALTER TABLE tryon_events DROP CONSTRAINT tryon_events_partner_id_fkey;

-- Seed keys: unused, partially used, exhausted (no created_by — public seed rows)
INSERT INTO dev_sandbox_keys (id, dev_key, created_by, used_count, max_count, created_at)
VALUES
    ('a1b2c3d4-0001-0001-0001-000000000001',
     'dev_YWJjZGVmZ2hpamtsb21u', NULL, 0, 20, now() - INTERVAL '3 days'),
    ('a1b2c3d4-0002-0002-0002-000000000002',
     'dev_bW5vcHFyc3R1dnd4eXph', NULL, 7, 20, now() - INTERVAL '2 days'),
    ('a1b2c3d4-0003-0003-0003-000000000003',
     'dev_YmNkZWZnaGlqa2xtbm9w', NULL, 20, 20, now() - INTERVAL '1 day');

-- Seed try-on events for the partially-used and exhausted keys
INSERT INTO tryon_events (device_id, platform, origin, partner_id, cloth_type, result, duration_ms)
VALUES
    ('seed-dev-1', 'web', 'dev_sandbox', 'a1b2c3d4-0002-0002-0002-000000000002', 'upper', 'success', 1240),
    ('seed-dev-2', 'web', 'dev_sandbox', 'a1b2c3d4-0002-0002-0002-000000000002', 'upper', 'success', 1080),
    ('seed-dev-3', 'ios', 'dev_sandbox', 'a1b2c3d4-0003-0003-0003-000000000003', 'upper', 'success', 970);
