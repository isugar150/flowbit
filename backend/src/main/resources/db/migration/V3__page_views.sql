-- When each person last had a page open, so the avatars at the top of a page can show everyone
-- who belongs there with how long ago they looked (docs/02-architecture.md "프레즌스", D-119).
-- `page_key` is the presence key of the realtime connection: `project:<id>` or `issue:<number>`.
CREATE TABLE page_views (
    page_key text        NOT NULL CHECK (char_length(page_key) BETWEEN 1 AND 100),
    user_id  uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    seen_at  timestamptz NOT NULL,
    PRIMARY KEY (page_key, user_id)
);

CREATE INDEX page_views_user_id_idx ON page_views (user_id);
