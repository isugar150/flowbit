-- Statuses an administrator can add or rename inside the five fixed categories, and estimates (D-228).
--
-- The category (issues.status: BACKLOG, READY, IN_PROGRESS, DONE, CANCELED) keeps deciding what a status
-- means (open or finished, what Git moves an issue to). issues.status_id says which of the statuses of that
-- category the issue is in, and only changes what it is called and where it stands on the board.

CREATE TABLE issue_statuses (
    id         uuid        PRIMARY KEY,
    category   text        NOT NULL CHECK (category IN ('BACKLOG', 'READY', 'IN_PROGRESS', 'DONE', 'CANCELED')),
    name       text        NOT NULL CHECK (char_length(name) BETWEEN 1 AND 30),
    -- Order inside the category; the categories themselves keep their fixed order.
    position   integer     NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX issue_statuses_name_key ON issue_statuses (lower(name));

-- The five that every installation starts with. Their ids are fixed so that code and tests can name them.
INSERT INTO issue_statuses (id, category, name, position) VALUES
    ('00000000-0000-7000-8000-0000000000a1', 'BACKLOG', '백로그', 10),
    ('00000000-0000-7000-8000-0000000000a2', 'READY', '준비됨', 10),
    ('00000000-0000-7000-8000-0000000000a3', 'IN_PROGRESS', '진행 중', 10),
    ('00000000-0000-7000-8000-0000000000a4', 'DONE', '완료', 10),
    ('00000000-0000-7000-8000-0000000000a5', 'CANCELED', '취소', 10);

ALTER TABLE issues ADD COLUMN status_id uuid REFERENCES issue_statuses (id);

UPDATE issues
SET status_id = (SELECT s.id FROM issue_statuses s WHERE s.category = issues.status ORDER BY s.position LIMIT 1);

ALTER TABLE issues ALTER COLUMN status_id SET NOT NULL;

CREATE INDEX issues_status_id_idx ON issues (status_id);

-- Estimates (story points), kept only where a project turns them on.
ALTER TABLE issues ADD COLUMN estimate integer CHECK (estimate IS NULL OR estimate BETWEEN 0 AND 1000);

ALTER TABLE projects ADD COLUMN estimates_enabled boolean NOT NULL DEFAULT false;
