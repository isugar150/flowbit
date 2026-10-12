CREATE TABLE projects (
    id                uuid        PRIMARY KEY,
    -- Prefix of issue keys (FLOW-12). Never changes once the project exists.
    key               text        NOT NULL CHECK (key ~ '^[A-Z][A-Z0-9]{1,9}$'),
    name              text        NOT NULL CHECK (char_length(name) BETWEEN 1 AND 80),
    description       text        NOT NULL DEFAULT '' CHECK (char_length(description) <= 500),
    next_issue_number integer     NOT NULL DEFAULT 1 CHECK (next_issue_number >= 1),
    created_by        uuid        NOT NULL REFERENCES users (id),
    archived_at       timestamptz,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT projects_key_key UNIQUE (key)
);

CREATE TABLE project_members (
    project_id uuid        NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    user_id    uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role       text        NOT NULL CHECK (role IN ('MANAGER', 'EDITOR', 'VIEWER')),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (project_id, user_id)
);

CREATE INDEX project_members_user_id_idx ON project_members (user_id);
