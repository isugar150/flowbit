-- Comments on the blocks of the editor documents, and the activity of the pages that had none
-- (D-165, docs/features/content-comments.md).

-- 1. Every top-level block of every stored document gets a number that does not change
--    (`attrs.blockId`), so that a comment can stay with its block when the block moves or the text
--    around it changes. The server gives numbers to blocks that come without one from now on.
CREATE FUNCTION flowbit_with_block_ids(doc jsonb) RETURNS jsonb
    LANGUAGE sql VOLATILE AS
$$
SELECT CASE
           WHEN doc IS NULL OR jsonb_typeof(doc -> 'content') IS DISTINCT FROM 'array' THEN doc
           ELSE jsonb_set(doc, '{content}', coalesce((SELECT jsonb_agg(
                                                                     CASE
                                                                         WHEN e -> 'attrs' ->> 'blockId' IS NOT NULL THEN e
                                                                         ELSE jsonb_set(e, '{attrs}', coalesce(e -> 'attrs', '{}'::jsonb) ||
                                                                                                      jsonb_build_object('blockId', gen_random_uuid()::text))
                                                                         END
                                                                     ORDER BY o)
                                                      FROM jsonb_array_elements(doc -> 'content') WITH ORDINALITY AS t (e, o)), '[]'::jsonb))
           END
$$;

UPDATE projects SET content = flowbit_with_block_ids(content) WHERE content IS NOT NULL;
UPDATE issues SET content = flowbit_with_block_ids(content) WHERE content IS NOT NULL;
UPDATE documents SET content = flowbit_with_block_ids(content) WHERE content IS NOT NULL;
UPDATE milestones SET content = flowbit_with_block_ids(content) WHERE content IS NOT NULL;

DROP FUNCTION flowbit_with_block_ids(jsonb);

-- 2. Threads of comments on a block. `block_id` is the block's number; `quote` is the start of its
--    text when the thread was opened, so that the thread still says what it was about once the block
--    is gone. The target is one of four kinds of row, so there is no foreign key: the retention job
--    removes what is left behind by a target that was deleted for good.
CREATE TABLE content_threads (
    id          uuid        PRIMARY KEY,
    target_type text        NOT NULL CHECK (target_type IN ('PROJECT', 'ISSUE', 'DOCUMENT', 'MILESTONE')),
    target_id   uuid        NOT NULL,
    block_id    text        NOT NULL,
    quote       text        NOT NULL DEFAULT '',
    created_by  uuid        NOT NULL REFERENCES users (id),
    created_at  timestamptz NOT NULL DEFAULT now(),
    resolved_at timestamptz,
    resolved_by uuid REFERENCES users (id)
);

CREATE INDEX content_threads_target_idx ON content_threads (target_type, target_id, created_at);

-- The comments of a thread: text and @ tags only. A deleted comment stays as a row and is never shown.
CREATE TABLE content_thread_comments (
    id         uuid        PRIMARY KEY,
    thread_id  uuid        NOT NULL REFERENCES content_threads (id) ON DELETE CASCADE,
    author_id  uuid        NOT NULL REFERENCES users (id),
    content    jsonb       NOT NULL,
    body       text        NOT NULL DEFAULT '',
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz
);

CREATE INDEX content_thread_comments_thread_idx ON content_thread_comments (thread_id, created_at, id);

-- 3. The activity of a project, a document and a milestone, as an issue has (`issue_activities`).
CREATE TABLE content_activities (
    id          uuid        PRIMARY KEY,
    -- Order of recording; timestamps can tie.
    seq         bigint      GENERATED ALWAYS AS IDENTITY,
    target_type text        NOT NULL CHECK (target_type IN ('PROJECT', 'DOCUMENT', 'MILESTONE')),
    target_id   uuid        NOT NULL,
    actor_id    uuid        NOT NULL REFERENCES users (id),
    type        text        NOT NULL,
    detail      jsonb       NOT NULL DEFAULT '{}',
    created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX content_activities_target_idx ON content_activities (target_type, target_id, seq);

-- 4. Comments under that activity, as an issue has (`issue_comments`), with emoji and files.
CREATE TABLE content_comments (
    id          uuid        PRIMARY KEY,
    target_type text        NOT NULL CHECK (target_type IN ('PROJECT', 'DOCUMENT', 'MILESTONE')),
    target_id   uuid        NOT NULL,
    author_id   uuid        NOT NULL REFERENCES users (id),
    content     jsonb       NOT NULL,
    body        text        NOT NULL DEFAULT '',
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    deleted_at  timestamptz
);

CREATE INDEX content_comments_target_idx ON content_comments (target_type, target_id, created_at, id);
CREATE INDEX content_comments_deleted_at_idx ON content_comments (deleted_at) WHERE deleted_at IS NOT NULL;

CREATE TABLE content_comment_reactions (
    comment_id uuid        NOT NULL REFERENCES content_comments (id) ON DELETE CASCADE,
    user_id    uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    emoji      text        NOT NULL CHECK (char_length(emoji) BETWEEN 1 AND 16),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (comment_id, user_id, emoji)
);

CREATE TABLE content_activity_reactions (
    activity_id uuid        NOT NULL REFERENCES content_activities (id) ON DELETE CASCADE,
    user_id     uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    emoji       text        NOT NULL CHECK (char_length(emoji) BETWEEN 1 AND 16),
    created_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (activity_id, user_id, emoji)
);

-- Files of those comments. As with an issue's comment files (V23), a file is uploaded before the
-- comment is sent: until then it is `pending`, belongs to no comment and is seen only by who
-- uploaded it; the retention job removes those nobody sent within a day.
CREATE TABLE content_files (
    id          uuid        PRIMARY KEY,
    target_type text        NOT NULL CHECK (target_type IN ('PROJECT', 'DOCUMENT', 'MILESTONE')),
    target_id   uuid        NOT NULL,
    comment_id  uuid REFERENCES content_comments (id) ON DELETE CASCADE,
    file_key    text        NOT NULL,
    file_name   text        NOT NULL CHECK (char_length(file_name) BETWEEN 1 AND 255),
    size_bytes  bigint      NOT NULL CHECK (size_bytes >= 0),
    uploaded_by uuid        NOT NULL REFERENCES users (id),
    pending     boolean     NOT NULL DEFAULT false,
    created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX content_files_target_idx ON content_files (target_type, target_id, created_at);
CREATE INDEX content_files_comment_idx ON content_files (comment_id) WHERE comment_id IS NOT NULL;
CREATE INDEX content_files_pending_idx ON content_files (created_at) WHERE pending;
