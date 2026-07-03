CREATE TABLE tryon_events (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    ts          TIMESTAMPTZ NOT NULL DEFAULT now(),
    device_id   VARCHAR(64),
    platform    VARCHAR(16) NOT NULL,
    origin      VARCHAR(16) NOT NULL,
    partner_id  UUID NOT NULL REFERENCES clients(id),
    product_id  VARCHAR(128),
    cloth_type  VARCHAR(32),
    result      VARCHAR(16) NOT NULL,
    fail_reason VARCHAR(255),
    duration_ms BIGINT
);

CREATE INDEX idx_tryon_events_partner_ts ON tryon_events (partner_id, ts);
CREATE INDEX idx_tryon_events_partner_origin_ts ON tryon_events (partner_id, origin, ts);
