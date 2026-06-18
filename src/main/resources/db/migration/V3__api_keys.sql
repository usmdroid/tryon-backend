CREATE TABLE api_keys (
    id           UUID PRIMARY KEY,
    client_id    UUID NOT NULL REFERENCES clients(id) ON DELETE CASCADE,
    name         VARCHAR(255) NOT NULL,
    key_prefix   VARCHAR(24)  NOT NULL,
    key_hash     VARCHAR(255) NOT NULL UNIQUE,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    last_used_at TIMESTAMPTZ,
    revoked_at   TIMESTAMPTZ
);
CREATE INDEX idx_api_keys_client_id ON api_keys(client_id);
