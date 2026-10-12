-- The whole schema in one file. While Flowbit is still in development there is no installed
-- database to carry forward, so the earlier step-by-step migrations were folded into this one
-- (docs/09-decisions.md D-067). From the first release on, changes go in V2, V3, … again.

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
    -- Issues are numbered across the whole workspace and shown as <issue_key>-<number>: FB-12.
    issue_key          text        NOT NULL DEFAULT 'FB' CHECK (issue_key ~ '^[A-Z][A-Z0-9]{1,9}$'),
    next_issue_number  integer     NOT NULL DEFAULT 1 CHECK (next_issue_number >= 1),
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
    -- File name of the uploaded profile image inside the storage directory, or null.
    avatar_key         text        CHECK (avatar_key ~ '^[0-9a-f-]{36}\.webp$'),
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

-- ---------------------------------------------------------------------------------------------
-- Projects
-- ---------------------------------------------------------------------------------------------

-- A project is the unit of access control, and something issues can be linked to.
CREATE TABLE projects (
    id          uuid        PRIMARY KEY,
    -- Short name in addresses (/project/FLOW). Never changes once the project exists.
    key         text        NOT NULL CHECK (key ~ '^[A-Z][A-Z0-9]{1,9}$'),
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
    CONSTRAINT projects_key_key UNIQUE (key),
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
    -- Unique across the workspace; shown after instance_settings.issue_key: FB-12.
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
    due_date     date,
    created_by   uuid        NOT NULL REFERENCES users (id),
    -- Incremented on every change; used later for conflict detection and realtime ordering.
    version      integer     NOT NULL DEFAULT 1,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT issues_number_key UNIQUE (number)
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
-- who can see the issue may add or remove themselves. Notifications (mail, push) go to these people.
CREATE TABLE issue_subscriptions (
    issue_id   uuid        NOT NULL REFERENCES issues (id) ON DELETE CASCADE,
    user_id    uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (issue_id, user_id)
);

CREATE INDEX issue_subscriptions_user_id_idx ON issue_subscriptions (user_id);

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

-- Documents that belong to an issue: a title and a body written in the same editor as the issue.
CREATE TABLE issue_documents (
    id         uuid        PRIMARY KEY,
    issue_id   uuid        NOT NULL REFERENCES issues (id) ON DELETE CASCADE,
    title      text        NOT NULL DEFAULT '' CHECK (char_length(title) <= 200),
    content    jsonb,
    created_by uuid        NOT NULL REFERENCES users (id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX issue_documents_issue_id_idx ON issue_documents (issue_id, created_at);
