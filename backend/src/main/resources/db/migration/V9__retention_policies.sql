-- How long deleted issues, documents and comments are kept before they are removed for good
-- (docs/features/admin-retention.md, D-132). One row per item; an item without a row is never
-- removed, which is how every installation starts.
CREATE TABLE retention_policies (
    item       text        PRIMARY KEY CHECK (item IN ('ISSUES', 'DOCUMENTS', 'COMMENTS')),
    amount     integer     NOT NULL CHECK (amount BETWEEN 1 AND 999),
    unit       text        NOT NULL CHECK (unit IN ('DAYS', 'WEEKS', 'MONTHS')),
    updated_at timestamptz NOT NULL DEFAULT now()
);

-- The purge looks for rows deleted before a moment.
CREATE INDEX issues_deleted_at_idx ON issues (deleted_at) WHERE deleted_at IS NOT NULL;
CREATE INDEX documents_deleted_at_idx ON documents (deleted_at) WHERE deleted_at IS NOT NULL;
CREATE INDEX issue_comments_deleted_at_idx ON issue_comments (deleted_at) WHERE deleted_at IS NOT NULL;
