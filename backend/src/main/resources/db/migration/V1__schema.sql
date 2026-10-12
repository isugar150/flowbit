-- The whole schema in one file: the baseline of the first production release. The step-by-step
-- migrations written during development were folded into this one, once, before that release
-- (docs/09-decisions.md D-067, D-104). This file is never edited again: every later change goes
-- in a new file, V2, V3, ….

-- ---------------------------------------------------------------------------------------------
-- Installation and people
-- ---------------------------------------------------------------------------------------------

-- Settings for the whole installation. Exactly one row (id = 1).
CREATE TABLE instance_settings (
    id                 smallint    PRIMARY KEY CHECK (id = 1),
    organization_name  text        NOT NULL DEFAULT 'Flowbit' CHECK (char_length(organization_name) BETWEEN 1 AND 100),
    -- File name of the uploaded logo inside the storage directory (FLOWBIT_STORAGE_PATH), or null.
    logo_key           text        CHECK (logo_key ~ '^[0-9a-f-]{36}\.webp$'),
    default_locale     text        NOT NULL DEFAULT 'ko' CHECK (default_locale IN ('ko', 'en')),
    default_timezone   text        NOT NULL DEFAULT 'Asia/Seoul',
    -- Issues are numbered across the whole workspace and shown as <workspace_key>-<number>: FB-12.
    workspace_key          text        NOT NULL DEFAULT 'FB' CHECK (workspace_key ~ '^[A-Z][A-Z0-9]{1,9}$'),
    next_issue_number  integer     NOT NULL DEFAULT 1 CHECK (next_issue_number >= 1),
    setup_completed_at timestamptz,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now()
);

INSERT INTO instance_settings (id) VALUES (1);

CREATE TABLE users (
    id                 uuid        PRIMARY KEY,
    -- A plain id or an e-mail address. Ids that come from a directory keep their own length, so
    -- the minimum of four characters is enforced by the application, for local accounts only.
    login_id           text        NOT NULL CHECK (login_id ~ '^[a-z0-9][a-z0-9._@+-]{0,253}$'),
    -- Where notification mail goes; also signs in.
    email              text        CHECK (email = lower(email) AND char_length(email) <= 254),
    display_name       text        CHECK (char_length(display_name) BETWEEN 1 AND 50),
    -- File name of the uploaded profile image inside the storage directory, or null.
    avatar_key         text        CHECK (avatar_key ~ '^[0-9a-f-]{36}\.webp$'),
    -- Where the account's password is checked. Only local accounts have a password here.
    auth_source        text        NOT NULL DEFAULT 'LOCAL' CHECK (auth_source IN ('LOCAL', 'LDAP', 'SSO')),
    -- The provider's own identifier for the person (OIDC `sub`); null for local and LDAP accounts.
    external_id        text,
    password_hash      text,
    -- Whether this person wants mail about the issues and projects they follow.
    notify_email       boolean     NOT NULL DEFAULT true,
    role              text        NOT NULL CHECK (role IN ('ADMIN', 'MEMBER')),
    status             text        NOT NULL CHECK (status IN ('ACTIVE', 'DISABLED')),
    locale             text        CHECK (locale IN ('ko', 'en')),
    timezone           text,
    failed_login_count integer     NOT NULL DEFAULT 0 CHECK (failed_login_count >= 0),
    locked_until       timestamptz,
    last_login_at      timestamptz,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT users_login_id_key UNIQUE (login_id),
    CONSTRAINT users_email_key UNIQUE (email),
    CONSTRAINT users_password_hash_check CHECK ((auth_source = 'LOCAL') = (password_hash IS NOT NULL))
);

CREATE UNIQUE INDEX users_external_id_key ON users (auth_source, external_id) WHERE external_id IS NOT NULL;

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

-- ---------------------------------------------------------------------------------------------
-- Sign-in through an LDAP directory or a single sign-on provider (docs/features/M2b-ldap-and-sso.md)
-- ---------------------------------------------------------------------------------------------

-- One row each (id = 1), like instance_settings.
CREATE TABLE ldap_settings (
    id                     smallint    PRIMARY KEY CHECK (id = 1),
    enabled                boolean     NOT NULL DEFAULT false,
    -- ldap://host:389 or ldaps://host:636
    url                    text        NOT NULL DEFAULT '',
    start_tls              boolean     NOT NULL DEFAULT false,
    -- The account used to look people up; empty for an anonymous search.
    bind_dn                text        NOT NULL DEFAULT '',
    bind_password          text        NOT NULL DEFAULT '',
    base_dn                text        NOT NULL DEFAULT '',
    -- {0} is replaced by what the person typed as their id.
    user_filter            text        NOT NULL DEFAULT '(|(uid={0})(mail={0}))',
    login_id_attribute     text        NOT NULL DEFAULT 'uid',
    email_attribute        text        NOT NULL DEFAULT 'mail',
    display_name_attribute text        NOT NULL DEFAULT 'cn',
    -- Create the account at the first successful sign-in.
    auto_provision         boolean     NOT NULL DEFAULT true,
    updated_at             timestamptz NOT NULL DEFAULT now()
);

INSERT INTO ldap_settings (id) VALUES (1);

CREATE TABLE sso_settings (
    id             smallint    PRIMARY KEY CHECK (id = 1),
    enabled        boolean     NOT NULL DEFAULT false,
    -- Label of the button on the sign-in page.
    display_name   text        NOT NULL DEFAULT 'SSO' CHECK (char_length(display_name) BETWEEN 1 AND 40),
    issuer         text        NOT NULL DEFAULT '',
    client_id      text        NOT NULL DEFAULT '',
    client_secret  text        NOT NULL DEFAULT '',
    scopes         text        NOT NULL DEFAULT 'openid profile email',
    -- The claim that becomes the login id; `email` is used when it is missing.
    login_id_claim text        NOT NULL DEFAULT 'preferred_username',
    auto_provision boolean     NOT NULL DEFAULT true,
    updated_at     timestamptz NOT NULL DEFAULT now(),
    -- OIDC: everything is read from the issuer's configuration document.
    -- OAUTH2: the addresses and the shape of the code exchange are spelled out below. Covers plain
    -- OAuth 2.0 (token, then a user-info call) and in-house providers that answer the code exchange
    -- with the person's profile.
    protocol       text        NOT NULL DEFAULT 'OIDC' CHECK (protocol IN ('OIDC', 'OAUTH2')),
    authorize_url  text        NOT NULL DEFAULT '',
    token_url      text        NOT NULL DEFAULT '',
    -- Empty: the answer of the code exchange is the profile.
    userinfo_url   text        NOT NULL DEFAULT '',
    -- How the client secret reaches the token address.
    token_auth     text        NOT NULL DEFAULT 'BASIC' CHECK (token_auth IN ('BASIC', 'BEARER', 'BODY')),
    token_format   text        NOT NULL DEFAULT 'FORM' CHECK (token_format IN ('FORM', 'JSON')),
    -- Empty: the standard OAuth 2.0 parameters. Otherwise a body with {{placeholders}}.
    token_body     text        NOT NULL DEFAULT '',
    -- Where in the profile the values are; a dot goes into a nested object (kakao_account.email).
    subject_field  text        NOT NULL DEFAULT 'sub',
    email_field    text        NOT NULL DEFAULT 'email',
    name_field     text        NOT NULL DEFAULT 'name',
    -- How the button on the sign-in page looks.
    button_style   text        NOT NULL DEFAULT 'DEFAULT' CHECK (button_style IN ('DEFAULT', 'NAVER_WORKS'))
);

INSERT INTO sso_settings (id) VALUES (1);

-- ---------------------------------------------------------------------------------------------
-- Projects
-- ---------------------------------------------------------------------------------------------

-- A project is the unit of access control, and something issues can be linked to.
CREATE TABLE projects (
    id          uuid        PRIMARY KEY,
    name        text        NOT NULL CHECK (char_length(name) BETWEEN 1 AND 80),
    -- The one-line summary. The longer description is `content`.
    description text        NOT NULL DEFAULT '' CHECK (char_length(description) <= 500),
    -- A document written in the block editor, stored as the editor's JSON (validated by RichText).
    content     jsonb,
    -- An uploaded image stored as WebP, like avatars. Null: the default box icon is shown.
    icon_key    text,
    status      text        NOT NULL DEFAULT 'BACKLOG'
                            CHECK (status IN ('BACKLOG', 'PLANNED', 'IN_PROGRESS', 'COMPLETED', 'CANCELED')),
    priority    text        NOT NULL DEFAULT 'NONE'
                            CHECK (priority IN ('NONE', 'URGENT', 'HIGH', 'MEDIUM', 'LOW')),
    lead_id     uuid        REFERENCES users (id) ON DELETE SET NULL,
    start_date  date,
    target_date date,
    -- Free-form tags of the project itself (not the issue labels below).
    labels      text[]      NOT NULL DEFAULT '{}' CHECK (cardinality(labels) <= 10),
    created_by  uuid        NOT NULL REFERENCES users (id),
    archived_at timestamptz,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT projects_dates_order CHECK (start_date IS NULL OR target_date IS NULL OR start_date <= target_date)
);

CREATE TABLE project_members (
    project_id uuid        NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    user_id    uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role       text        NOT NULL CHECK (role IN ('MANAGER', 'EDITOR', 'VIEWER')),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (project_id, user_id)
);

CREATE INDEX project_members_user_id_idx ON project_members (user_id);

-- People who follow a whole project: they are told about every change to its issues, the same
-- way the subscribers of one issue are (docs/features/M7a-notification-channels.md, D-102).
CREATE TABLE project_subscriptions (
    project_id uuid        NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    user_id    uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (project_id, user_id)
);

CREATE INDEX project_subscriptions_user_id_idx ON project_subscriptions (user_id);

-- A short note on how the project is going, with a health value. The latest one is the project's health.
CREATE TABLE project_updates (
    id         uuid        PRIMARY KEY,
    project_id uuid        NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    author_id  uuid        NOT NULL REFERENCES users (id),
    health     text        NOT NULL CHECK (health IN ('ON_TRACK', 'AT_RISK', 'OFF_TRACK')),
    body       text        NOT NULL DEFAULT '' CHECK (char_length(body) <= 5000),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX project_updates_project_created_idx ON project_updates (project_id, created_at DESC);

-- Stages inside a project ("목표" on screen). Issues linked to the project can be put in one;
-- a milestone's progress is the share of its issues that are done.
CREATE TABLE milestones (
    id          uuid        PRIMARY KEY,
    project_id  uuid        NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    name        text        NOT NULL CHECK (char_length(name) BETWEEN 1 AND 80),
    target_date date,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX milestones_project_id_idx ON milestones (project_id);

-- ---------------------------------------------------------------------------------------------
-- Issues
-- ---------------------------------------------------------------------------------------------

-- An issue stands on its own. It may be linked to a project (and to a milestone inside it), and
-- the link can change without the issue's number changing.
CREATE TABLE issues (
    id           uuid        PRIMARY KEY,
    -- Unique across the workspace; shown after instance_settings.workspace_key: FB-12.
    number       integer     NOT NULL CHECK (number >= 1),
    -- Null: linked to no project, and then open to everyone.
    project_id   uuid        REFERENCES projects (id) ON DELETE SET NULL,
    -- Deleting a milestone keeps its issues; they just belong to no milestone.
    milestone_id uuid        REFERENCES milestones (id) ON DELETE SET NULL,
    -- Sub-issues: removing the parent leaves the children as ordinary issues.
    parent_id    uuid        REFERENCES issues (id) ON DELETE SET NULL,
    title        text        NOT NULL CHECK (char_length(title) BETWEEN 1 AND 200),
    -- Plain text extracted from `content`, for previews and search.
    description  text        NOT NULL DEFAULT '' CHECK (char_length(description) <= 50000),
    -- The body as the block editor's JSON (validated by RichText).
    content      jsonb,
    status       text        NOT NULL DEFAULT 'BACKLOG'
                             CHECK (status IN ('BACKLOG', 'READY', 'IN_PROGRESS', 'DONE', 'CANCELED')),
    priority     text        NOT NULL DEFAULT 'NONE'
                             CHECK (priority IN ('NONE', 'URGENT', 'HIGH', 'MEDIUM', 'LOW')),
    assignee_id  uuid        REFERENCES users (id) ON DELETE SET NULL,
    -- One date (due_date alone) or a span: start_date is only set together with a due date.
    start_date   date,
    due_date     date,
    created_by   uuid        NOT NULL REFERENCES users (id),
    -- Incremented on every change; used later for conflict detection and realtime ordering.
    version      integer     NOT NULL DEFAULT 1,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    -- Deleting an issue only marks it: it leaves the lists but stays readable with its history,
    -- and can be restored.
    deleted_at   timestamptz,
    deleted_by   uuid        REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT issues_number_key UNIQUE (number),
    CONSTRAINT issues_dates_order CHECK (start_date IS NULL OR (due_date IS NOT NULL AND start_date <= due_date))
);

CREATE INDEX issues_project_status_idx ON issues (project_id, status);
CREATE INDEX issues_assignee_id_idx ON issues (assignee_id);
CREATE INDEX issues_created_by_idx ON issues (created_by);
CREATE INDEX issues_milestone_id_idx ON issues (milestone_id);
CREATE INDEX issues_parent_id_idx ON issues (parent_id);

-- Labels shared by the whole workspace and put on issues (several per issue). Anyone can add a
-- label; the three below are there from the start.
CREATE TABLE labels (
    id         uuid        PRIMARY KEY,
    name       text        NOT NULL CHECK (char_length(name) BETWEEN 1 AND 30),
    color      text        NOT NULL CHECK (color ~ '^#[0-9a-f]{6}$'),
    created_at timestamptz NOT NULL DEFAULT now()
);

-- "Bug" and "bug" are the same label.
CREATE UNIQUE INDEX labels_name_key ON labels (lower(name));

-- Labels are listed oldest first, so the times set the order: 버그, 기능, 중요.
INSERT INTO labels (id, name, color, created_at) VALUES
    (gen_random_uuid(), '버그', '#e5484d', now() - interval '2 seconds'),
    (gen_random_uuid(), '기능', '#8e6ee0', now() - interval '1 second'),
    (gen_random_uuid(), '중요', '#f2994a', now());

CREATE TABLE issue_labels (
    issue_id uuid NOT NULL REFERENCES issues (id) ON DELETE CASCADE,
    label_id uuid NOT NULL REFERENCES labels (id) ON DELETE CASCADE,
    PRIMARY KEY (issue_id, label_id)
);

CREATE INDEX issue_labels_label_id_idx ON issue_labels (label_id);

-- What happened to an issue, in order: created, status changed, assigned … (the "활동" list on
-- the issue). `detail` holds the old and new values as they were named at the time, so the list
-- still reads right after a person, label or milestone is renamed or removed.
CREATE TABLE issue_activities (
    id         uuid        PRIMARY KEY,
    -- Order of recording; timestamps can tie.
    seq        bigint      GENERATED ALWAYS AS IDENTITY,
    issue_id   uuid        NOT NULL REFERENCES issues (id) ON DELETE CASCADE,
    actor_id   uuid        NOT NULL REFERENCES users (id),
    type       text        NOT NULL,
    detail     jsonb       NOT NULL DEFAULT '{}',
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX issue_activities_issue_id_idx ON issue_activities (issue_id, seq);

-- Who wants to hear about an issue. The author and the assignee are added automatically; anyone
-- who can see the issue may add or remove themselves. Notifications go to these people and to
-- whoever follows the issue's project (project_subscriptions).
CREATE TABLE issue_subscriptions (
    issue_id   uuid        NOT NULL REFERENCES issues (id) ON DELETE CASCADE,
    user_id    uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (issue_id, user_id)
);

CREATE INDEX issue_subscriptions_user_id_idx ON issue_subscriptions (user_id);

-- Comments on issues (docs/features/M3-projects-and-issues.md "댓글"). The body is an editor
-- document like an issue's; people tagged in it with @ are mention nodes inside `content`.
CREATE TABLE issue_comments (
    id         uuid        PRIMARY KEY,
    issue_id   uuid        NOT NULL REFERENCES issues (id) ON DELETE CASCADE,
    author_id  uuid        NOT NULL REFERENCES users (id),
    content    jsonb       NOT NULL,
    -- Plain text of `content`, for notifications and search.
    body       text        NOT NULL DEFAULT '',
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    -- A deleted comment stays as a row and is never shown.
    deleted_at timestamptz
);

CREATE INDEX issue_comments_issue_id_idx ON issue_comments (issue_id, created_at, id);

-- Emoji reactions on an issue: one row per person and emoji.
CREATE TABLE issue_reactions (
    issue_id   uuid        NOT NULL REFERENCES issues (id) ON DELETE CASCADE,
    user_id    uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    emoji      text        NOT NULL CHECK (char_length(emoji) BETWEEN 1 AND 16),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (issue_id, user_id, emoji)
);

-- Files attached to an issue. The bytes are in the storage directory under `file_key`.
CREATE TABLE issue_attachments (
    id          uuid        PRIMARY KEY,
    issue_id    uuid        NOT NULL REFERENCES issues (id) ON DELETE CASCADE,
    file_key    text        NOT NULL,
    file_name   text        NOT NULL CHECK (char_length(file_name) BETWEEN 1 AND 255),
    size_bytes  bigint      NOT NULL CHECK (size_bytes >= 0),
    uploaded_by uuid        NOT NULL REFERENCES users (id),
    created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX issue_attachments_issue_id_idx ON issue_attachments (issue_id, created_at);

-- Documents: a title and a body written in the block editor. Each belongs either to an issue
-- (listed under it as "자료") or to a project (made from the project's overview), never to both.
CREATE TABLE documents (
    id         uuid        PRIMARY KEY,
    issue_id   uuid        REFERENCES issues (id) ON DELETE CASCADE,
    project_id uuid        REFERENCES projects (id) ON DELETE CASCADE,
    -- The document this one sits inside (a page block in its body). Only project documents nest,
    -- five levels deep at most; the service checks both. Gone together with its parent.
    parent_id  uuid        REFERENCES documents (id) ON DELETE CASCADE,
    title      text        NOT NULL DEFAULT '' CHECK (char_length(title) <= 200),
    content    jsonb,
    created_by uuid        NOT NULL REFERENCES users (id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT documents_one_owner CHECK ((issue_id IS NULL) <> (project_id IS NULL))
);

CREATE INDEX documents_issue_id_idx ON documents (issue_id, created_at);
CREATE INDEX documents_project_id_idx ON documents (project_id, created_at);
CREATE INDEX documents_parent_id_idx ON documents (parent_id, created_at);

-- ---------------------------------------------------------------------------------------------
-- Notification channels: mail (SMTP), web push (Firebase Cloud Messaging) and outgoing API calls
-- (webhooks). docs/features/M7a-notification-channels.md
-- ---------------------------------------------------------------------------------------------

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

-- Outgoing API calls: each enabled row's address is called once for each person a change to an
-- issue is told to (the subscribers of the issue and of its project).
CREATE TABLE webhooks (
    id              uuid        PRIMARY KEY,
    name            text        NOT NULL CHECK (char_length(name) BETWEEN 1 AND 60),
    enabled         boolean     NOT NULL DEFAULT true,
    url             text        NOT NULL,
    method          text        NOT NULL DEFAULT 'POST' CHECK (method IN ('GET', 'POST', 'PUT', 'PATCH', 'DELETE')),
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
