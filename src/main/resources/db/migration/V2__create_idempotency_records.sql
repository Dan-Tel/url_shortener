CREATE TABLE idempotency_records (
     owner_id VARCHAR(128) NOT NULL,
     idempotency_key VARCHAR(255) NOT NULL,

     original_url TEXT NOT NULL,
     ttl_seconds BIGINT NOT NULL,

     short_url VARCHAR(8)
         REFERENCES short_urls(short_url),

     created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

     PRIMARY KEY (owner_id, idempotency_key),

     CONSTRAINT chk_idempotency_ttl
         CHECK (ttl_seconds > 0)
);