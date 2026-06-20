-- Attribute try-on usage (TRYON_DEBIT rows) to the API key that produced it.
-- Nullable: legacy/session calls without a resolvable key write NULL.
ALTER TABLE credit_transactions ADD COLUMN api_key_id UUID NULL REFERENCES api_keys(id);

-- Supports the by-key and time-series aggregations grouped/filtered by api_key_id.
CREATE INDEX idx_credit_txn_apikey_created ON credit_transactions(api_key_id, created_at);
