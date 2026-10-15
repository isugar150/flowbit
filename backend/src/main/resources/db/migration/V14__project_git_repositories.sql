-- The Git repositories a project's work happens in, set by the project's managers (D-148). A project
-- with repositories here takes Git events only from them, whatever the connection's allowed
-- projects say; a project without any follows the connection's allowed projects as before.
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
