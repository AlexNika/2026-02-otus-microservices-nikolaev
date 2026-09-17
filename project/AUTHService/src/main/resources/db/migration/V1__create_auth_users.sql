CREATE TABLE auth_users
(
    id               BIGSERIAL PRIMARY KEY,
    version          BIGINT       NOT NULL,
    created          TIMESTAMP    NOT NULL,
    updated          TIMESTAMP    NOT NULL,
    created_by       VARCHAR(255),
    last_modified_by VARCHAR(255),
    email            VARCHAR(255) NOT NULL,
    password_hash    VARCHAR(255) NOT NULL,
    CONSTRAINT uq_auth_users_email UNIQUE (email)
);

CREATE INDEX idx_auth_users_email ON auth_users (email);
