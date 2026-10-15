-- Which Git user each member is, for the servers whose events carry no usable e-mail address
-- (GitHub's pull requests, GitLab's hidden addresses). Set by an administrator in the Git
-- settings; matched after the e-mail address and before the login id (D-147).
CREATE TABLE git_user_accounts (
    user_id    uuid        PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    -- Lower case: Git user names are compared without case.
    username   text        NOT NULL UNIQUE CHECK (username = lower(username) AND char_length(username) BETWEEN 1 AND 100),
    updated_at timestamptz NOT NULL DEFAULT now()
);
