-- Issues stand on their own (D-062): they are numbered across the whole workspace and shown as
-- <workspace issue key>-<number>, for example FB-12. A project is something an issue may be
-- linked to, not where it lives, so the link can be changed or removed without renumbering.
ALTER TABLE instance_settings
    ADD COLUMN issue_key         text    NOT NULL DEFAULT 'FB' CHECK (issue_key ~ '^[A-Z][A-Z0-9]{1,9}$'),
    ADD COLUMN next_issue_number integer NOT NULL DEFAULT 1;

ALTER TABLE issues DROP CONSTRAINT issues_project_number_key;
ALTER TABLE issues ALTER COLUMN project_id DROP NOT NULL;

-- Existing issues get new numbers in the order they were created.
WITH ordered AS (
    SELECT id, row_number() OVER (ORDER BY created_at, project_id, number) AS n FROM issues
)
UPDATE issues i SET number = o.n FROM ordered o WHERE o.id = i.id;

ALTER TABLE issues ADD CONSTRAINT issues_number_key UNIQUE (number);
UPDATE instance_settings SET next_issue_number = (SELECT coalesce(max(number), 0) + 1 FROM issues);

-- Sub-issues: an issue may have a parent. Removing the parent leaves the children as ordinary issues.
ALTER TABLE issues ADD COLUMN parent_id uuid REFERENCES issues (id) ON DELETE SET NULL;
CREATE INDEX issues_parent_id_idx ON issues (parent_id);

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
    id           uuid        PRIMARY KEY,
    issue_id     uuid        NOT NULL REFERENCES issues (id) ON DELETE CASCADE,
    file_key     text        NOT NULL,
    file_name    text        NOT NULL CHECK (char_length(file_name) BETWEEN 1 AND 255),
    size_bytes   bigint      NOT NULL CHECK (size_bytes >= 0),
    uploaded_by  uuid        NOT NULL REFERENCES users (id),
    created_at   timestamptz NOT NULL DEFAULT now()
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
