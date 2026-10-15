-- An issue can have several assignees (D-125). They move from issues.assignee_id to a table of
-- their own; created_at keeps the order they were given the issue in, which is the order shown.
CREATE TABLE issue_assignees (
    issue_id   uuid        NOT NULL REFERENCES issues (id) ON DELETE CASCADE,
    user_id    uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (issue_id, user_id)
);

-- "내 티켓": the issues a person is one of the assignees of.
CREATE INDEX issue_assignees_user_id_idx ON issue_assignees (user_id);

INSERT INTO issue_assignees (issue_id, user_id, created_at)
SELECT id, assignee_id, updated_at FROM issues WHERE assignee_id IS NOT NULL;

DROP INDEX issues_assignee_id_idx;
ALTER TABLE issues DROP COLUMN assignee_id;
