CREATE TABLE app_user (
    id                    BIGSERIAL    PRIMARY KEY,
    email                 VARCHAR(254) NOT NULL UNIQUE,
    auth_verifier_hash    VARCHAR(64)  NOT NULL,
    password_salt         VARCHAR(64)  NOT NULL,
    wrapped_master_key    VARCHAR(128) NOT NULL,
    wrapped_master_key_iv VARCHAR(64)  NOT NULL,
    quota_bytes           BIGINT       NOT NULL,
    used_bytes            BIGINT       NOT NULL DEFAULT 0,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE user_session (
    token_hash   VARCHAR(128) PRIMARY KEY,
    user_id      BIGINT       NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_seen_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at   TIMESTAMPTZ  NOT NULL
);

CREATE INDEX ix_user_session_user ON user_session (user_id);

CREATE TABLE stored_file (
    id                     BIGSERIAL    PRIMARY KEY,
    user_id                BIGINT       NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    path_hash              VARCHAR(128) NOT NULL,
    encrypted_virtual_path VARCHAR(4096) NOT NULL,
    virtual_path_iv        VARCHAR(64)  NOT NULL,
    blob_id                VARCHAR(64),
    size_bytes             BIGINT       NOT NULL DEFAULT 0,
    stored_size_bytes      BIGINT       NOT NULL DEFAULT 0,
    is_folder              BOOLEAN      NOT NULL DEFAULT FALSE,
    in_trash               BOOLEAN      NOT NULL DEFAULT FALSE,
    trashed_at             TIMESTAMPTZ,
    starred                BOOLEAN      NOT NULL DEFAULT FALSE,
    modified_at            TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_stored_file_active_path
    ON stored_file (user_id, path_hash)
    WHERE NOT in_trash;

CREATE INDEX ix_stored_file_user ON stored_file (user_id);
