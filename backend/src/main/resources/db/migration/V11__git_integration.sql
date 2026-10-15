-- Git server connections (docs/features/git-integration.md, D-141). An administrator adds one per
-- Git server (or organization); the server calls /api/v1/integrations/git/{id}/webhook, signed
-- with `secret`, and Flowbit links the issues whose keys it finds to branches, pull requests and
-- commits.
CREATE TABLE git_connections (
    id               uuid        PRIMARY KEY,
    name             text        NOT NULL CHECK (char_length(name) BETWEEN 1 AND 80),
    provider         text        NOT NULL CHECK (provider IN ('GITEA', 'GITLAB', 'GITHUB')),
    -- Shown and used in the setup guide only; the server calls Flowbit, not the other way round.
    base_url         text        NOT NULL,
    -- Stored as it is, like the other integration settings (D-090).
    secret           text        NOT NULL,
    enabled          boolean     NOT NULL DEFAULT true,
    -- False: only the issues of the projects in git_connection_projects are linked and changed.
    all_projects     boolean     NOT NULL DEFAULT true,
    auto_in_progress boolean     NOT NULL DEFAULT true,
    auto_done        boolean     NOT NULL DEFAULT true,
    -- The last call from the Git server, for checking the setup.
    last_received_at timestamptz,
    last_event       text,
    -- JSON as text (the entity maps it as a string): {"linkedIssues": ["FB-12"], "statusChanged": ["FB-12"], "skipped": 0}
    last_result      text,
    last_error       text,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE git_connection_projects (
    connection_id uuid NOT NULL REFERENCES git_connections (id) ON DELETE CASCADE,
    project_id    uuid NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    PRIMARY KEY (connection_id, project_id)
);

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

-- A change a Git server made may belong to nobody in Flowbit: the history then keeps only the Git
-- user name, in detail.via = {"source": "git", "name": "kimjm"}.
ALTER TABLE issue_activities ALTER COLUMN actor_id DROP NOT NULL;
