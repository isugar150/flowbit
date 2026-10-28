-- Relations between issues: one blocks another, one is a duplicate of another, or they are simply related (D-230).
--
-- A row says "issue_id <type> related_id":
--   BLOCKS      issue_id blocks related_id (so related_id is blocked by issue_id)
--   DUPLICATES  issue_id is a duplicate of related_id (the original)
--   RELATED     the two are related; stored once, with the smaller id first
CREATE TABLE issue_relations (
    id         uuid        PRIMARY KEY,
    issue_id   uuid        NOT NULL REFERENCES issues (id) ON DELETE CASCADE,
    related_id uuid        NOT NULL REFERENCES issues (id) ON DELETE CASCADE,
    type       text        NOT NULL CHECK (type IN ('BLOCKS', 'DUPLICATES', 'RELATED')),
    created_by uuid        REFERENCES users (id) ON DELETE SET NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT issue_relations_distinct CHECK (issue_id <> related_id),
    CONSTRAINT issue_relations_key UNIQUE (issue_id, related_id, type)
);

CREATE INDEX issue_relations_related_idx ON issue_relations (related_id);
