CREATE EXTENSION IF NOT EXISTS "pgcrypto";

CREATE TABLE users (
                       id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                       email         VARCHAR(255) NOT NULL UNIQUE,
                       password_hash VARCHAR(255) NOT NULL,
                       created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE languages (
                           id           BIGSERIAL PRIMARY KEY,
                           name         VARCHAR(50)  NOT NULL,
                           version      VARCHAR(50)  NOT NULL,
                           docker_image VARCHAR(255) NOT NULL,
                           source_file  VARCHAR(100) NOT NULL,
                           run_command  VARCHAR(255) NOT NULL,
                           UNIQUE (name, version)
);

CREATE TABLE submissions (
                             id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                             user_id       UUID   NOT NULL REFERENCES users(id),
                             language_id   BIGINT NOT NULL REFERENCES languages(id),
                             source_code   TEXT   NOT NULL,
                             stdin         TEXT,
                             status        VARCHAR(20) NOT NULL DEFAULT 'QUEUED',
                             stdout        TEXT,
                             stderr        TEXT,
                             exit_code     INTEGER,
                             exec_time_ms  BIGINT,
                             memory_kb     BIGINT,
                             created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
                             completed_at  TIMESTAMPTZ
);

CREATE INDEX idx_submissions_user_created ON submissions (user_id, created_at DESC);