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
