CREATE TABLE issues (
    id          uuid        PRIMARY KEY,
    project_id  uuid        NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    -- Per-project sequence; shown as <project key>-<number>, for example FLOW-12.
    number      integer     NOT NULL CHECK (number >= 1),
    title       text        NOT NULL CHECK (char_length(title) BETWEEN 1 AND 200),
    description text        NOT NULL DEFAULT '' CHECK (char_length(description) <= 50000),
    preset      text        CHECK (preset IN ('BUG', 'ENHANCEMENT', 'FEATURE')),
    status      text        NOT NULL DEFAULT 'BACKLOG'
                            CHECK (status IN ('BACKLOG', 'READY', 'IN_PROGRESS', 'DONE', 'CANCELED')),
    priority    text        NOT NULL DEFAULT 'NONE'
                            CHECK (priority IN ('NONE', 'URGENT', 'HIGH', 'MEDIUM', 'LOW')),
    assignee_id uuid        REFERENCES users (id) ON DELETE SET NULL,
    due_date    date,
    created_by  uuid        NOT NULL REFERENCES users (id),
    -- Incremented on every change; used later for conflict detection and realtime ordering.
    version     integer     NOT NULL DEFAULT 1,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT issues_project_number_key UNIQUE (project_id, number)
);

CREATE INDEX issues_project_status_idx ON issues (project_id, status);
CREATE INDEX issues_assignee_id_idx ON issues (assignee_id);
