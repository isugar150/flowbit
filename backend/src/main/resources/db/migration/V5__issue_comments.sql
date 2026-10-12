-- Comments on issues (docs/features/M3-projects-and-issues.md "댓글"). The body is an editor
-- document like an issue's; people tagged in it with @ are mention nodes inside `content`.
CREATE TABLE issue_comments (
    id         uuid        PRIMARY KEY,
    issue_id   uuid        NOT NULL REFERENCES issues (id) ON DELETE CASCADE,
    author_id  uuid        NOT NULL REFERENCES users (id),
    content    jsonb       NOT NULL,
    -- Plain text of `content`, for notifications and search.
    body       text        NOT NULL DEFAULT '',
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    -- A deleted comment stays as a row and is never shown.
    deleted_at timestamptz
);

CREATE INDEX issue_comments_issue_id_idx ON issue_comments (issue_id, created_at, id);
