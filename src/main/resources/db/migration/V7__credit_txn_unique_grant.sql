-- Prevent duplicate FREE_GRANT rows per client under concurrent grantFree() calls.
-- A partial index is used so PURCHASE and TRYON_DEBIT rows remain unrestricted.
CREATE UNIQUE INDEX idx_credit_txn_unique_free_grant
    ON credit_transactions(client_id, type)
    WHERE type = 'FREE_GRANT';
