-- Notification channels: mail (SMTP), web push (Firebase Cloud Messaging) and outgoing API calls
-- (webhooks). docs/features/M7a-notification-channels.md

-- Whether this person wants mail about the issues they follow. The address is users.email.
ALTER TABLE users ADD COLUMN notify_email boolean NOT NULL DEFAULT true;

-- One row (id = 1), like instance_settings. Passwords and keys are never returned by the API.
CREATE TABLE notification_settings (
    id                      smallint    PRIMARY KEY CHECK (id = 1),
    smtp_enabled            boolean     NOT NULL DEFAULT false,
    smtp_host               text        NOT NULL DEFAULT '',
    smtp_port               integer     NOT NULL DEFAULT 587 CHECK (smtp_port BETWEEN 1 AND 65535),
    smtp_security           text        NOT NULL DEFAULT 'STARTTLS' CHECK (smtp_security IN ('NONE', 'STARTTLS', 'SSL')),
    smtp_username           text        NOT NULL DEFAULT '',
    smtp_password           text        NOT NULL DEFAULT '',
    smtp_from_address       text        NOT NULL DEFAULT '',
    smtp_from_name          text        NOT NULL DEFAULT 'Flowbit',
    push_enabled            boolean     NOT NULL DEFAULT false,
    -- The service account key file (JSON) the server signs its FCM requests with.
    fcm_service_account     text        NOT NULL DEFAULT '',
    -- The web app's public configuration, handed to browsers so they can register for push.
    fcm_api_key             text        NOT NULL DEFAULT '',
    fcm_messaging_sender_id text        NOT NULL DEFAULT '',
    fcm_app_id              text        NOT NULL DEFAULT '',
    fcm_vapid_key           text        NOT NULL DEFAULT '',
    updated_at              timestamptz NOT NULL DEFAULT now()
);

INSERT INTO notification_settings (id) VALUES (1);

-- One row per browser that agreed to receive push notifications.
CREATE TABLE push_tokens (
    token        text        PRIMARY KEY CHECK (char_length(token) BETWEEN 1 AND 4096),
    user_id      uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    user_agent   text,
    created_at   timestamptz NOT NULL,
    last_seen_at timestamptz NOT NULL
);

CREATE INDEX push_tokens_user_id_idx ON push_tokens (user_id);

-- Outgoing API calls: every issue change is sent to each enabled row's address.
CREATE TABLE webhooks (
    id              uuid        PRIMARY KEY,
    name            text        NOT NULL CHECK (char_length(name) BETWEEN 1 AND 60),
    enabled         boolean     NOT NULL DEFAULT true,
    url             text        NOT NULL,
    method          text        NOT NULL DEFAULT 'POST' CHECK (method IN ('POST', 'PUT', 'PATCH')),
    content_type    text        NOT NULL DEFAULT 'application/json',
    -- A JSON object of header name to value, kept as text so the row maps onto a plain entity.
    headers         text        NOT NULL DEFAULT '{}',
    -- Empty means the standard JSON payload; otherwise text with {{placeholders}}.
    body_template   text        NOT NULL DEFAULT '',
    -- When set, the body is signed: X-Flowbit-Signature: sha256=<hmac>.
    signing_secret  text        NOT NULL DEFAULT '',
    -- Comma-separated IssueActivityType names; empty means every kind of change.
    event_types     text        NOT NULL DEFAULT '',
    last_status     integer,
    last_error      text,
    last_attempt_at timestamptz,
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL
);
