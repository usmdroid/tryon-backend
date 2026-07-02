ALTER TABLE credit_transactions ADD COLUMN moderation_status VARCHAR(20) NOT NULL DEFAULT 'VISIBLE';
CREATE INDEX idx_credit_txn_moderation ON credit_transactions (moderation_status);
