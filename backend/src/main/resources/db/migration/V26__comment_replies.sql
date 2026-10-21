-- Replies to comments (D-167, docs/features/content-comments.md "답글"): a comment on an issue, or
-- under the activity of a project, a document or a milestone, can be answered. One level only: a
-- reply cannot be answered (the services check it). A comment that was deleted while it has
-- replies stays as a row, marked deleted, and the page shows "삭제된 댓글" in its place; the retention
-- job leaves it until its replies can go too.
ALTER TABLE issue_comments
    ADD COLUMN parent_id uuid REFERENCES issue_comments (id) ON DELETE CASCADE;

CREATE INDEX issue_comments_parent_idx ON issue_comments (parent_id) WHERE parent_id IS NOT NULL;

ALTER TABLE content_comments
    ADD COLUMN parent_id uuid REFERENCES content_comments (id) ON DELETE CASCADE;

CREATE INDEX content_comments_parent_idx ON content_comments (parent_id) WHERE parent_id IS NOT NULL;
