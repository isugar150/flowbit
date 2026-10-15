-- GitHub is connected as a GitHub App (docs/features/git-integration.md "GitHub: GitHub App 으로 연결",
-- D-146). The app is made from a manifest and installed on one organization. Events arrive by the
-- app's webhook, or are read from the GitHub API every few minutes when Flowbit cannot be reached
-- from the internet.
ALTER TABLE git_connections
    -- WEBHOOK: the server calls Flowbit. POLL: Flowbit reads the GitHub API (GitHub only).
    ADD COLUMN receive_mode text NOT NULL DEFAULT 'WEBHOOK' CHECK (receive_mode IN ('WEBHOOK', 'POLL')),
    ADD COLUMN github_organization text,
    ADD COLUMN github_app_id bigint,
    ADD COLUMN github_app_slug text,
    ADD COLUMN github_client_id text,
    -- Stored as they are, like the other integration settings (D-090).
    ADD COLUMN github_client_secret text,
    ADD COLUMN github_private_key text,
    ADD COLUMN github_installation_id bigint,
    -- 'all' or 'selected', as GitHub says it.
    ADD COLUMN github_repository_selection text,
    -- One-time value that ties GitHub's redirect after the app is made to this connection.
    ADD COLUMN github_setup_state text,
    ADD COLUMN last_synced_at timestamptz,
    ADD COLUMN sync_error text,
    ADD COLUMN rate_limit_remaining integer;

CREATE UNIQUE INDEX git_connections_github_setup_state_key ON git_connections (github_setup_state) WHERE github_setup_state IS NOT NULL;

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
