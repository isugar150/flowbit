-- Who follows a document (D-166, docs/features/content-comments.md "구독과 알림"). A document is
-- followed on its own, apart from the project or the issue it is in: its author follows it from the
-- start, so does anyone tagged in a comment on it, and anyone who has the document open may follow
-- or stop following it. Whoever follows it is told about what happens to the document (its name,
-- its body, deleting and restoring it); a comment is only told to the people tagged in it.
CREATE TABLE document_subscriptions (
    document_id uuid        NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    user_id     uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (document_id, user_id)
);

CREATE INDEX document_subscriptions_user_id_idx ON document_subscriptions (user_id);

-- The documents there already are: their authors follow them.
INSERT INTO document_subscriptions (document_id, user_id, created_at)
SELECT id, created_by, created_at FROM documents
ON CONFLICT DO NOTHING;
