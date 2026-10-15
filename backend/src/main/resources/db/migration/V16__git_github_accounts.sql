-- The GitHub account each member connected in their own settings, through the GitHub App's user
-- authorization (OAuth, D-150). Matched for GitHub events before the Git user names an
-- administrator set (git_user_accounts, which stay for GitLab, Gitea and people who did not connect).
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
