CREATE TABLE instance_settings (
    id                 smallint    PRIMARY KEY CHECK (id = 1),
    organization_name  text        NOT NULL DEFAULT 'Flowbit' CHECK (char_length(organization_name) BETWEEN 1 AND 100),
    default_locale     text        NOT NULL DEFAULT 'ko' CHECK (default_locale IN ('ko', 'en')),
    default_timezone   text        NOT NULL DEFAULT 'Asia/Seoul',
    setup_completed_at timestamptz,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now()
);

INSERT INTO instance_settings (id) VALUES (1);

CREATE TABLE users (
    id                 uuid        PRIMARY KEY,
    login_id           text        NOT NULL CHECK (login_id ~ '^[a-z0-9][a-z0-9._-]{3,31}$'),
    email              text        CHECK (email = lower(email) AND char_length(email) <= 254),
    display_name       text        CHECK (char_length(display_name) BETWEEN 1 AND 50),
    password_hash      text        NOT NULL,
    role               text        NOT NULL CHECK (role IN ('ADMIN', 'MEMBER')),
    status             text        NOT NULL CHECK (status IN ('ACTIVE', 'DISABLED')),
    locale             text        CHECK (locale IN ('ko', 'en')),
    timezone           text,
    failed_login_count integer     NOT NULL DEFAULT 0 CHECK (failed_login_count >= 0),
    locked_until       timestamptz,
    last_login_at      timestamptz,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT users_login_id_key UNIQUE (login_id),
    CONSTRAINT users_email_key UNIQUE (email)
);

-- One row per signed-in browser. The cookie holds a random token; only its SHA-256 hash is stored.
CREATE TABLE user_sessions (
    id           uuid        PRIMARY KEY,
    token_hash   text        NOT NULL,
    user_id      uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at   timestamptz NOT NULL,
    last_seen_at timestamptz NOT NULL,
    ip_address   inet,
    user_agent   text,
    CONSTRAINT user_sessions_token_hash_key UNIQUE (token_hash)
);

CREATE INDEX user_sessions_user_id_idx ON user_sessions (user_id);
CREATE INDEX user_sessions_last_seen_at_idx ON user_sessions (last_seen_at);

CREATE TABLE audit_events (
    id            uuid        PRIMARY KEY,
    occurred_at   timestamptz NOT NULL,
    actor_user_id uuid        REFERENCES users (id),
    event_type    text        NOT NULL,
    ip_address    inet,
    user_agent    text,
    detail        jsonb       NOT NULL DEFAULT '{}'::jsonb
);

CREATE INDEX audit_events_occurred_at_idx ON audit_events (occurred_at);
CREATE INDEX audit_events_actor_occurred_at_idx ON audit_events (actor_user_id, occurred_at);
