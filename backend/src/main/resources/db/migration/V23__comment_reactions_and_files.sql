-- Emoji on the entries of an issue's history (D-164): on a comment and on a line of the activity
-- ("님이 상태를 바꿨습니다"). One row per person and emoji, like issue_reactions. They go with the
-- comment or the entry.
CREATE TABLE issue_comment_reactions (
    comment_id uuid        NOT NULL REFERENCES issue_comments (id) ON DELETE CASCADE,
    user_id    uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    emoji      text        NOT NULL CHECK (char_length(emoji) BETWEEN 1 AND 16),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (comment_id, user_id, emoji)
);

CREATE TABLE issue_activity_reactions (
    activity_id uuid        NOT NULL REFERENCES issue_activities (id) ON DELETE CASCADE,
    user_id     uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    emoji       text        NOT NULL CHECK (char_length(emoji) BETWEEN 1 AND 16),
    created_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (activity_id, user_id, emoji)
);

-- Files on a comment (D-164) share the table of the issue's own attached files. `comment_id` says
-- which comment holds the file; those files are not part of the issue's "첨부 파일" list. A file
-- is uploaded before the comment is sent: until then it is `pending` (visible only to who uploaded
-- it) and has no comment. Pending files nobody sent within a day are removed by the retention job.
ALTER TABLE issue_attachments
    ADD COLUMN comment_id uuid REFERENCES issue_comments (id) ON DELETE CASCADE,
    ADD COLUMN pending boolean NOT NULL DEFAULT false;

CREATE INDEX issue_attachments_comment_id_idx ON issue_attachments (comment_id) WHERE comment_id IS NOT NULL;
CREATE INDEX issue_attachments_pending_idx ON issue_attachments (created_at) WHERE pending;
