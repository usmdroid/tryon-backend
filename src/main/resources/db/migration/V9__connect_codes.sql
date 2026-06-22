-- OAuth-uslubida kalit ulash uchun bir martalik kodlar jadvali.
-- authorize → code yaratadi (TTL ~5 daq); exchange → consume qilib sk_ kalit qaytaradi.
CREATE TABLE connect_codes (
    id          UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    code_hash   VARCHAR     NOT NULL UNIQUE,
    client_id   UUID        NOT NULL REFERENCES clients(id),
    api_key_id  UUID        NOT NULL REFERENCES api_keys(id),
    redirect_uri TEXT       NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
