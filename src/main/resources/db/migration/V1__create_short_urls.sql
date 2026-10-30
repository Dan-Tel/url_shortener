CREATE TABLE short_urls (
    short_url VARCHAR(8) PRIMARY KEY,
    original_url TEXT NOT NULL,
    owner_id VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL,
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,

    CONSTRAINT chk_expires_after_created
        CHECK (expires_at > created_at)
);