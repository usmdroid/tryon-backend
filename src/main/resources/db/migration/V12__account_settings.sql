CREATE TABLE secondary_emails (
    id          UUID      PRIMARY KEY DEFAULT gen_random_uuid(),
    client_id   UUID      NOT NULL REFERENCES clients(id) ON DELETE CASCADE,
    email       VARCHAR   NOT NULL,
    verified_at TIMESTAMP NULL,
    created_at  TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE(client_id, email)
);

CREATE TABLE account_audit_log (
    id         UUID      PRIMARY KEY DEFAULT gen_random_uuid(),
    client_id  UUID      NOT NULL,
    action     VARCHAR   NOT NULL,
    detail     VARCHAR,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);
