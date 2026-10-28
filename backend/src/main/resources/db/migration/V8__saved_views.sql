-- Saved views of the issue lists: the filters, order, grouping and way of showing (list, board, timeline, calendar) a person
-- keeps under a name, for themselves or for everyone (D-229).
CREATE TABLE saved_views (
    id         uuid        PRIMARY KEY,
    owner_id   uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name       text        NOT NULL CHECK (char_length(name) BETWEEN 1 AND 40),
    -- Which list the view belongs to: all issues, my assigned issues, my requested issues.
    path       text        NOT NULL CHECK (path IN ('/issues', '/my-issues', '/my-issues/created')),
    -- The list's own query string without the leading "?" (status=…&view=board…).
    query      text        NOT NULL DEFAULT '' CHECK (char_length(query) <= 1000),
    shared     boolean     NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX saved_views_owner_idx ON saved_views (owner_id);
CREATE INDEX saved_views_shared_idx ON saved_views (shared) WHERE shared;
