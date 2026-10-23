-- The whole schema in one file: the baseline of the first production install. The step-by-step
-- migrations written during development (V1 to V26, and then V1 to V4 on top of the first merge)
-- were folded into this one (docs/09-decisions.md D-067, D-104, D-168, D-191). A database that
-- already has the older files applied keeps its data: its flyway_schema_history is rewritten to match
-- this file (the SQL is in D-191). This file is never edited again: every later change goes in a
-- new file, V2, V3, ….

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
    -- When the person first finished or skipped the onboarding tour (docs/features/onboarding-tour.md,
    -- D-120). Null means they have not seen it, so the app shows it the next time they open it.
    tour_completed_at  timestamptz,
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

-- Someone who may not enter a project asks to be let in (D-128). Only waiting requests are kept:
-- approving or turning one down removes the row.
CREATE TABLE project_access_requests (
    project_id uuid        NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    user_id    uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (project_id, user_id)
);

CREATE INDEX project_access_requests_user_id_idx ON project_access_requests (user_id);

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

-- ---------------------------------------------------------------------------------------------
-- Issues
-- ---------------------------------------------------------------------------------------------

-- An issue stands on its own. It may be linked to a project, and
-- the link can change without the issue's number changing.
CREATE TABLE issues (
    id           uuid        PRIMARY KEY,
    -- Unique across the workspace; shown after instance_settings.workspace_key: FB-12.
    number       integer     NOT NULL CHECK (number >= 1),
    -- Null: linked to no project, and then open to everyone.
    project_id   uuid        REFERENCES projects (id) ON DELETE SET NULL,
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
CREATE INDEX issues_created_by_idx ON issues (created_by);
CREATE INDEX issues_parent_id_idx ON issues (parent_id);
-- The purge looks for rows deleted before a moment (D-132).
CREATE INDEX issues_deleted_at_idx ON issues (deleted_at) WHERE deleted_at IS NOT NULL;

-- An issue can have several assignees (D-125). created_at keeps the order they were given the issue
-- in, which is the order shown.
CREATE TABLE issue_assignees (
    issue_id   uuid        NOT NULL REFERENCES issues (id) ON DELETE CASCADE,
    user_id    uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (issue_id, user_id)
);

-- "내 티켓": the issues a person is one of the assignees of.
CREATE INDEX issue_assignees_user_id_idx ON issue_assignees (user_id);

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
-- still reads right after a person or a label is renamed or removed.
CREATE TABLE issue_activities (
    id         uuid        PRIMARY KEY,
    -- Order of recording; timestamps can tie.
    seq        bigint      GENERATED ALWAYS AS IDENTITY,
    issue_id   uuid        NOT NULL REFERENCES issues (id) ON DELETE CASCADE,
    -- Null when a Git server made the change for someone who has no account here: the history then
    -- keeps only the Git user name, in detail.via = {"source": "git", "name": "kimjm"} (D-141).
    actor_id   uuid        REFERENCES users (id),
    type       text        NOT NULL,
    detail     jsonb       NOT NULL DEFAULT '{}',
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX issue_activities_issue_id_idx ON issue_activities (issue_id, seq);

-- Emoji on a line of an issue's history ("님이 상태를 바꿨습니다", D-164). One row per person and
-- emoji, like issue_reactions. They go with the entry.
CREATE TABLE issue_activity_reactions (
    activity_id uuid        NOT NULL REFERENCES issue_activities (id) ON DELETE CASCADE,
    user_id     uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    emoji       text        NOT NULL CHECK (char_length(emoji) BETWEEN 1 AND 16),
    created_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (activity_id, user_id, emoji)
);

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
    -- The comment this one answers; null for a comment of its own (D-167). One level only: a reply
    -- cannot be answered (the service checks it). A comment deleted while it has replies stays as a
    -- row, marked deleted, and the page shows "삭제된 댓글" in its place; the purge leaves it until
    -- its replies can go too.
    parent_id  uuid        REFERENCES issue_comments (id) ON DELETE CASCADE,
    content    jsonb       NOT NULL,
    -- Plain text of `content`, for notifications and search.
    body       text        NOT NULL DEFAULT '',
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    -- A deleted comment stays as a row and is never shown.
    deleted_at timestamptz
);

CREATE INDEX issue_comments_issue_id_idx ON issue_comments (issue_id, created_at, id);
CREATE INDEX issue_comments_parent_idx ON issue_comments (parent_id) WHERE parent_id IS NOT NULL;
CREATE INDEX issue_comments_deleted_at_idx ON issue_comments (deleted_at) WHERE deleted_at IS NOT NULL;

-- Emoji on a comment (D-164). One row per person and emoji. They go with the comment.
CREATE TABLE issue_comment_reactions (
    comment_id uuid        NOT NULL REFERENCES issue_comments (id) ON DELETE CASCADE,
    user_id    uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    emoji      text        NOT NULL CHECK (char_length(emoji) BETWEEN 1 AND 16),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (comment_id, user_id, emoji)
);

-- Emoji reactions on an issue: one row per person and emoji.
CREATE TABLE issue_reactions (
    issue_id   uuid        NOT NULL REFERENCES issues (id) ON DELETE CASCADE,
    user_id    uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    emoji      text        NOT NULL CHECK (char_length(emoji) BETWEEN 1 AND 16),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (issue_id, user_id, emoji)
);

-- Files attached to an issue, or sent with one of its comments (D-164). The bytes are in the storage
-- (the storage directory or an S3 bucket, D-167) under `file_key`. `comment_id` says which comment
-- holds the file; those files are not part of the issue's "첨부 파일" list. A file is uploaded before
-- the comment is sent: until then it is `pending` (visible only to who uploaded it) and has no
-- comment. Pending files nobody sent within a day are removed by the retention job.
CREATE TABLE issue_attachments (
    id          uuid        PRIMARY KEY,
    issue_id    uuid        NOT NULL REFERENCES issues (id) ON DELETE CASCADE,
    comment_id  uuid        REFERENCES issue_comments (id) ON DELETE CASCADE,
    file_key    text        NOT NULL,
    file_name   text        NOT NULL CHECK (char_length(file_name) BETWEEN 1 AND 255),
    size_bytes  bigint      NOT NULL CHECK (size_bytes >= 0),
    uploaded_by uuid        NOT NULL REFERENCES users (id),
    pending     boolean     NOT NULL DEFAULT false,
    created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX issue_attachments_issue_id_idx ON issue_attachments (issue_id, created_at);
CREATE INDEX issue_attachments_comment_id_idx ON issue_attachments (comment_id) WHERE comment_id IS NOT NULL;
CREATE INDEX issue_attachments_pending_idx ON issue_attachments (created_at) WHERE pending;

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
    -- Deleting a document archives it instead of removing the row (D-121): it leaves the lists, can
    -- still be opened read-only, and can be restored. Who archived it and when is kept here.
    deleted_at timestamptz,
    deleted_by uuid        REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT documents_one_owner CHECK ((issue_id IS NULL) <> (project_id IS NULL))
);

CREATE INDEX documents_issue_id_idx ON documents (issue_id, created_at);
CREATE INDEX documents_project_id_idx ON documents (project_id, created_at);
CREATE INDEX documents_parent_id_idx ON documents (parent_id, created_at);
CREATE INDEX documents_deleted_by_idx ON documents (deleted_by) WHERE deleted_by IS NOT NULL;
CREATE INDEX documents_deleted_at_idx ON documents (deleted_at) WHERE deleted_at IS NOT NULL;

-- Who follows a document (D-166). A document is followed on its own, apart from the project or the
-- issue it is in: its author follows it from the start, so does anyone tagged in a comment on it, and
-- anyone who has the document open may follow or stop following it. Whoever follows it is told about
-- what happens to the document (its name, its body, deleting and restoring it); a comment is only
-- told to the people tagged in it (and, for a reply, the people in the conversation, D-167).
CREATE TABLE document_subscriptions (
    document_id uuid        NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    user_id     uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (document_id, user_id)
);

CREATE INDEX document_subscriptions_user_id_idx ON document_subscriptions (user_id);

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

-- ---------------------------------------------------------------------------------------------
-- Presence, retention, search
-- ---------------------------------------------------------------------------------------------

-- When each person last had a page open, so the avatars at the top of a page can show everyone
-- who belongs there with how long ago they looked (docs/02-architecture.md "프레즌스", D-119).
-- `page_key` is the presence key of the realtime connection: `project:<id>`, `issue:<number>` or
-- `document:<id>`.
CREATE TABLE page_views (
    page_key text        NOT NULL CHECK (char_length(page_key) BETWEEN 1 AND 100),
    user_id  uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    seen_at  timestamptz NOT NULL,
    PRIMARY KEY (page_key, user_id)
);

CREATE INDEX page_views_user_id_idx ON page_views (user_id);

-- How long deleted issues, documents and comments are kept before they are removed for good
-- (docs/features/admin-retention.md, D-132). One row per item; an item without a row is never
-- removed, which is how every installation starts.
CREATE TABLE retention_policies (
    item       text        PRIMARY KEY CHECK (item IN ('ISSUES', 'DOCUMENTS', 'COMMENTS')),
    amount     integer     NOT NULL CHECK (amount BETWEEN 1 AND 999),
    unit       text        NOT NULL CHECK (unit IN ('DAYS', 'WEEKS', 'MONTHS')),
    updated_at timestamptz NOT NULL DEFAULT now()
);

-- The initial consonants of Korean syllables, for searching titles by them: '결제 개편' -> 'ㄱㅈ ㄱㅍ'
-- (docs/features/search.md, D-133). Anything that is not a Hangul syllable is kept as it is.
CREATE FUNCTION hangul_initials(input text) RETURNS text
    LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE
AS $$
    SELECT coalesce(string_agg(
        CASE
            WHEN ascii(ch) BETWEEN 44032 AND 55203
                THEN substr('ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ', (ascii(ch) - 44032) / 588 + 1, 1)
            ELSE ch
        END, '' ORDER BY position), '')
    FROM unnest(string_to_array(input, NULL)) WITH ORDINALITY AS chars(ch, position)
$$;

-- ---------------------------------------------------------------------------------------------
-- Git integration (docs/features/git-integration.md, D-141 and after)
-- ---------------------------------------------------------------------------------------------

-- Git server connections. An administrator adds one per Git server (or organization); the server
-- calls /api/v1/integrations/git/{id}/webhook, signed with `secret`, and Flowbit links the issues
-- whose keys it finds to branches, pull requests and commits. GitHub is connected as a GitHub App
-- (D-146): made from a manifest and installed on one organization; events arrive by the app's
-- webhook, or are read from the GitHub API every few minutes when Flowbit cannot be reached from
-- the internet. Which projects a connection reaches is each project's own choice (D-148, D-149).
CREATE TABLE git_connections (
    id                          uuid        PRIMARY KEY,
    name                        text        NOT NULL CHECK (char_length(name) BETWEEN 1 AND 80),
    provider                    text        NOT NULL CHECK (provider IN ('GITEA', 'GITLAB', 'GITHUB')),
    -- Shown and used in the setup guide only; the server calls Flowbit, not the other way round.
    base_url                    text        NOT NULL,
    -- Stored as it is, like the other integration settings (D-090).
    secret                      text        NOT NULL,
    enabled                     boolean     NOT NULL DEFAULT true,
    -- What each Git stage moves an issue to (D-158): a JSON object from stage (BRANCH, PR_DRAFT,
    -- PR_OPEN, PR_MERGED, PR_CLOSED, CLOSING_COMMIT) to a status, or null for none.
    status_rules                text        NOT NULL DEFAULT '{}',
    -- WEBHOOK: the server calls Flowbit. POLL: Flowbit reads the GitHub API (GitHub only).
    receive_mode                text        NOT NULL DEFAULT 'WEBHOOK' CHECK (receive_mode IN ('WEBHOOK', 'POLL')),
    github_organization         text,
    github_app_id               bigint,
    github_app_slug             text,
    github_client_id            text,
    -- Stored as they are, like the other integration settings (D-090).
    github_client_secret        text,
    github_private_key          text,
    github_installation_id      bigint,
    -- 'all' or 'selected', as GitHub says it.
    github_repository_selection text,
    -- One-time value that ties GitHub's redirect after the app is made to this connection.
    github_setup_state          text,
    -- The last call from the Git server, for checking the setup.
    last_received_at            timestamptz,
    last_event                  text,
    -- JSON as text (the entity maps it as a string): {"linkedIssues": ["FB-12"], "statusChanged": ["FB-12"], "skipped": 0}
    last_result                 text,
    last_error                  text,
    last_synced_at              timestamptz,
    sync_error                  text,
    rate_limit_remaining        integer,
    created_at                  timestamptz NOT NULL DEFAULT now(),
    updated_at                  timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX git_connections_github_setup_state_key ON git_connections (github_setup_state) WHERE github_setup_state IS NOT NULL;

-- A branch or a pull request an issue is linked to through its key.
CREATE TABLE issue_git_links (
    id            uuid        PRIMARY KEY,
    issue_id      uuid        NOT NULL REFERENCES issues (id) ON DELETE CASCADE,
    connection_id uuid        NOT NULL REFERENCES git_connections (id) ON DELETE CASCADE,
    kind          text        NOT NULL CHECK (kind IN ('BRANCH', 'PULL_REQUEST')),
    -- org/web
    repository    text        NOT NULL,
    -- The branch, or the branch a pull request comes from.
    branch        text,
    pr_number     integer,
    title         text,
    url           text        NOT NULL,
    pr_state      text        CHECK (pr_state IN ('OPEN', 'DRAFT', 'MERGED', 'CLOSED')),
    -- Merging it finishes the issue: the key is in the branch name or the title, or follows
    -- "Fixes" and the like. Otherwise it only refers to the issue.
    closes        boolean     NOT NULL DEFAULT false,
    -- A branch or pull request someone took off the issue (D-161). The row stays, marked, so that the
    -- next push or event for it does not link it again; such links are not shown and do not move the
    -- issue.
    unlinked_at   timestamptz,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    CHECK ((kind = 'PULL_REQUEST') = (pr_number IS NOT NULL))
);

CREATE UNIQUE INDEX issue_git_links_key ON issue_git_links (issue_id, connection_id, repository, kind, coalesce(pr_number, 0), coalesce(branch, ''));
CREATE INDEX issue_git_links_connection_idx ON issue_git_links (connection_id);

-- Commits that named an issue. One row per issue and commit, so a delivery that comes again, or a
-- commit moved to another branch, is not recorded twice.
CREATE TABLE issue_git_commits (
    issue_id      uuid        NOT NULL REFERENCES issues (id) ON DELETE CASCADE,
    connection_id uuid        NOT NULL REFERENCES git_connections (id) ON DELETE CASCADE,
    repository    text        NOT NULL,
    sha           text        NOT NULL,
    -- The first line.
    message       text        NOT NULL,
    url           text        NOT NULL,
    author_name   text,
    committed_at  timestamptz,
    PRIMARY KEY (issue_id, sha)
);

CREATE INDEX issue_git_commits_connection_idx ON issue_git_commits (connection_id);

-- How far reading a repository from the GitHub API has got, so that each round only handles what is new.
CREATE TABLE git_sync_cursors (
    connection_id    uuid        NOT NULL REFERENCES git_connections (id) ON DELETE CASCADE,
    -- org/web
    repository       text        NOT NULL,
    pulls_etag       text,
    -- The newest pull request change already handled.
    pulls_updated_at timestamptz,
    branches_etag    text,
    -- JSON object: branch name -> head commit, as last seen.
    branch_heads     text        NOT NULL DEFAULT '{}',
    updated_at       timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (connection_id, repository)
);

-- The Git repositories a project's work happens in, set by the project's managers (D-148). A project
-- with repositories here takes Git events only from them; a project without any takes events from
-- every repository.
CREATE TABLE project_git_repositories (
    project_id    uuid        NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    connection_id uuid        NOT NULL REFERENCES git_connections (id) ON DELETE CASCADE,
    -- org/web, lower case: Git servers compare repository names without case.
    repository    text        NOT NULL CHECK (repository = lower(repository)),
    created_at    timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (project_id, connection_id, repository)
);

CREATE INDEX project_git_repositories_connection_idx ON project_git_repositories (connection_id, repository);

-- The one repository an issue's work is in, picked on the issue when its project has several
-- (D-148). While it is one of the project's repositories, only events from it reach the issue.
CREATE TABLE issue_git_repositories (
    issue_id      uuid        PRIMARY KEY REFERENCES issues (id) ON DELETE CASCADE,
    connection_id uuid        NOT NULL REFERENCES git_connections (id) ON DELETE CASCADE,
    repository    text        NOT NULL CHECK (repository = lower(repository)),
    updated_at    timestamptz NOT NULL DEFAULT now()
);

-- The GitHub account each member connected in their own settings, through the GitHub App's user
-- authorization (OAuth, D-150). People are otherwise matched by their e-mail address and login id
-- (D-152).
CREATE TABLE git_github_accounts (
    user_id        uuid        PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    -- The GitHub App (connection) it was connected through; its server is where the login counts.
    connection_id  uuid        NOT NULL REFERENCES git_connections (id) ON DELETE CASCADE,
    -- Lower case: GitHub logins are compared without case.
    github_login   text        NOT NULL CHECK (github_login = lower(github_login)),
    github_user_id bigint      NOT NULL,
    linked_at      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (connection_id, github_user_id)
);

CREATE INDEX git_github_accounts_login_idx ON git_github_accounts (github_login);

-- The repositories each Git connection is known to have (D-151), for project managers to pick
-- from. A GitHub App's list is the repositories it is installed on, read from the GitHub API; a
-- Gitea or GitLab connection's list is the repositories that sent it events.
CREATE TABLE git_repositories (
    connection_id uuid        NOT NULL REFERENCES git_connections (id) ON DELETE CASCADE,
    -- org/web, lower case.
    repository    text        NOT NULL CHECK (repository = lower(repository)),
    -- The repository's page, when known.
    url           text,
    seen_at       timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (connection_id, repository)
);

-- ---------------------------------------------------------------------------------------------
-- Comments on blocks, and the activity and comments of projects and documents
-- (docs/features/content-comments.md, D-165 to D-167)
-- ---------------------------------------------------------------------------------------------

-- Threads of comments on a block. `block_id` is the block's number (`attrs.blockId` of a top-level
-- block of the editor document: it does not change when the block moves or the text around it
-- changes); `quote` is the start of its text when the thread was opened, so that the thread still
-- says what it was about once the block is gone. The target is one of three kinds of row, so there
-- is no foreign key: the retention job removes what is left behind by a target that was deleted
-- for good.
CREATE TABLE content_threads (
    id          uuid        PRIMARY KEY,
    target_type text        NOT NULL CHECK (target_type IN ('PROJECT', 'ISSUE', 'DOCUMENT')),
    target_id   uuid        NOT NULL,
    block_id    text        NOT NULL,
    quote       text        NOT NULL DEFAULT '',
    created_by  uuid        NOT NULL REFERENCES users (id),
    created_at  timestamptz NOT NULL DEFAULT now(),
    resolved_at timestamptz,
    resolved_by uuid        REFERENCES users (id)
);

CREATE INDEX content_threads_target_idx ON content_threads (target_type, target_id, created_at);

-- The comments of a thread: text and @ tags only. A deleted comment stays as a row and is never shown.
CREATE TABLE content_thread_comments (
    id         uuid        PRIMARY KEY,
    thread_id  uuid        NOT NULL REFERENCES content_threads (id) ON DELETE CASCADE,
    author_id  uuid        NOT NULL REFERENCES users (id),
    content    jsonb       NOT NULL,
    body       text        NOT NULL DEFAULT '',
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz
);

CREATE INDEX content_thread_comments_thread_idx ON content_thread_comments (thread_id, created_at, id);

-- The activity of a project and a document, as an issue has (`issue_activities`).
CREATE TABLE content_activities (
    id          uuid        PRIMARY KEY,
    -- Order of recording; timestamps can tie.
    seq         bigint      GENERATED ALWAYS AS IDENTITY,
    target_type text        NOT NULL CHECK (target_type IN ('PROJECT', 'DOCUMENT')),
    target_id   uuid        NOT NULL,
    actor_id    uuid        NOT NULL REFERENCES users (id),
    type        text        NOT NULL,
    detail      jsonb       NOT NULL DEFAULT '{}',
    created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX content_activities_target_idx ON content_activities (target_type, target_id, seq);

-- Comments under that activity, as an issue has (`issue_comments`), with replies, emoji and files.
CREATE TABLE content_comments (
    id          uuid        PRIMARY KEY,
    target_type text        NOT NULL CHECK (target_type IN ('PROJECT', 'DOCUMENT')),
    target_id   uuid        NOT NULL,
    author_id   uuid        NOT NULL REFERENCES users (id),
    -- The comment this one answers; null for a comment of its own (D-167). As on an issue's comments.
    parent_id   uuid        REFERENCES content_comments (id) ON DELETE CASCADE,
    content     jsonb       NOT NULL,
    body        text        NOT NULL DEFAULT '',
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    deleted_at  timestamptz
);

CREATE INDEX content_comments_target_idx ON content_comments (target_type, target_id, created_at, id);
CREATE INDEX content_comments_parent_idx ON content_comments (parent_id) WHERE parent_id IS NOT NULL;
CREATE INDEX content_comments_deleted_at_idx ON content_comments (deleted_at) WHERE deleted_at IS NOT NULL;

CREATE TABLE content_comment_reactions (
    comment_id uuid        NOT NULL REFERENCES content_comments (id) ON DELETE CASCADE,
    user_id    uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    emoji      text        NOT NULL CHECK (char_length(emoji) BETWEEN 1 AND 16),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (comment_id, user_id, emoji)
);

CREATE TABLE content_activity_reactions (
    activity_id uuid        NOT NULL REFERENCES content_activities (id) ON DELETE CASCADE,
    user_id     uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    emoji       text        NOT NULL CHECK (char_length(emoji) BETWEEN 1 AND 16),
    created_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (activity_id, user_id, emoji)
);

-- Files of those comments. As with an issue's comment files, a file is uploaded before the comment
-- is sent: until then it is `pending`, belongs to no comment and is seen only by who uploaded it;
-- the retention job removes those nobody sent within a day.
CREATE TABLE content_files (
    id          uuid        PRIMARY KEY,
    target_type text        NOT NULL CHECK (target_type IN ('PROJECT', 'DOCUMENT')),
    target_id   uuid        NOT NULL,
    comment_id  uuid        REFERENCES content_comments (id) ON DELETE CASCADE,
    file_key    text        NOT NULL,
    file_name   text        NOT NULL CHECK (char_length(file_name) BETWEEN 1 AND 255),
    size_bytes  bigint      NOT NULL CHECK (size_bytes >= 0),
    uploaded_by uuid        NOT NULL REFERENCES users (id),
    pending     boolean     NOT NULL DEFAULT false,
    created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX content_files_target_idx ON content_files (target_type, target_id, created_at);
CREATE INDEX content_files_comment_idx ON content_files (comment_id) WHERE comment_id IS NOT NULL;
CREATE INDEX content_files_pending_idx ON content_files (created_at) WHERE pending;

-- ---------------------------------------------------------------------------------------------
-- Properties people add to one project or issue on their own (docs/features/custom-properties.md, D-186)
-- ---------------------------------------------------------------------------------------------

-- A label, a kind of value and the value. They belong to the one item they were added to (no shared
-- definition), and go with it when it is deleted for good. Kept apart from the item's own columns so
-- the two kinds of item share one table; exactly one of the two ids is set.
CREATE TABLE custom_properties (
    id         uuid        PRIMARY KEY,
    project_id uuid        REFERENCES projects (id) ON DELETE CASCADE,
    issue_id   uuid        REFERENCES issues (id) ON DELETE CASCADE,
    label      text        NOT NULL CHECK (char_length(label) BETWEEN 1 AND 30),
    type       text        NOT NULL CHECK (type IN ('TEXT', 'NUMBER', 'DATE', 'CHECKBOX', 'LINK', 'PERSON')),
    -- null: no value yet. TEXT and LINK a string, NUMBER a number, DATE 'YYYY-MM-DD', CHECKBOX a boolean,
    -- PERSON the user's id as a string.
    value      jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT custom_properties_one_owner CHECK (num_nonnulls(project_id, issue_id) = 1)
);

-- One label once per item, ignoring case.
CREATE UNIQUE INDEX custom_properties_project_label_idx ON custom_properties (project_id, lower(label)) WHERE project_id IS NOT NULL;
CREATE UNIQUE INDEX custom_properties_issue_label_idx ON custom_properties (issue_id, lower(label)) WHERE issue_id IS NOT NULL;
