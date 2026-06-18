-- Kalit turi: 'secret' (sk_, server) yoki 'publishable' (pk_, brauzer + domen allowlist).
-- pk_ ning yagona himoyasi domen ro'yxati (vergul bilan ajratilgan).
ALTER TABLE api_keys ADD COLUMN key_type VARCHAR(16) NOT NULL DEFAULT 'secret';
ALTER TABLE api_keys ADD COLUMN allowed_domains TEXT;
