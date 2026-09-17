CREATE TABLE refresh_tokens
(
    id               BIGSERIAL PRIMARY KEY,
    version          BIGINT      NOT NULL,
    created          TIMESTAMP   NOT NULL,
    updated          TIMESTAMP   NOT NULL,
    created_by       VARCHAR(255),
    last_modified_by VARCHAR(255),
    user_id          BIGINT      NOT NULL REFERENCES auth_users (id) ON DELETE CASCADE,
    token_hash       VARCHAR(64) NOT NULL,
    rotated          BOOLEAN     NOT NULL DEFAULT FALSE,
    expires_at       TIMESTAMP   NOT NULL,
    CONSTRAINT uq_refresh_tokens_token_hash UNIQUE (token_hash)
);

CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens (user_id);
