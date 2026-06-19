CREATE TABLE wallets (
    client_id      UUID    PRIMARY KEY REFERENCES clients(id) ON DELETE CASCADE,
    balance_msim   BIGINT  NOT NULL DEFAULT 0,
    total_requests BIGINT  NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE credit_transactions (
    id                 UUID        PRIMARY KEY,
    client_id          UUID        NOT NULL REFERENCES clients(id) ON DELETE CASCADE,
    amount_msim        BIGINT      NOT NULL,
    type               VARCHAR(32) NOT NULL,
    balance_after_msim BIGINT      NOT NULL,
    meta               TEXT,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_credit_txn_client_created ON credit_transactions(client_id, created_at);

-- Backfill: existing clients get a wallet (100 sim = 100000 msim) + FREE_GRANT ledger row
INSERT INTO wallets (client_id, balance_msim, total_requests, created_at, updated_at)
SELECT id, 100000, 0, now(), now()
FROM clients
WHERE NOT EXISTS (SELECT 1 FROM wallets w WHERE w.client_id = clients.id);

INSERT INTO credit_transactions (id, client_id, amount_msim, type, balance_after_msim, meta, created_at)
SELECT gen_random_uuid(), id, 100000, 'FREE_GRANT', 100000, 'Boshlang''ich bepul sim', now()
FROM clients
WHERE NOT EXISTS (
    SELECT 1 FROM credit_transactions ct
    WHERE ct.client_id = clients.id AND ct.type = 'FREE_GRANT'
);
