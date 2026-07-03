CREATE TABLE app_config (
    config_key   VARCHAR(100) PRIMARY KEY,
    config_value VARCHAR(255) NOT NULL,
    updated_at   TIMESTAMPTZ,
    updated_by   UUID NULL REFERENCES clients(id)
);

-- TRYON_ENABLED: GPU kill switch. Default ON (true). Toggle via /api/admin/tryon-flag.
INSERT INTO app_config (config_key, config_value, updated_at, updated_by)
VALUES ('TRYON_ENABLED', 'true', now(), NULL);
