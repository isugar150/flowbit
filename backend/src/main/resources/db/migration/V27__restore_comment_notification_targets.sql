-- Older alerts stored the page and a text preview but not the comment ID.
-- Recover only a unique match: never choose a "nearest" comment when the evidence is ambiguous.
WITH sources AS (
    SELECT c.id, c.author_id, c.created_at, c.parent_id IS NOT NULL AS reply,
           'ISSUE' AS target_type, i.number::text AS target_key,
           left(coalesce(nullif(c.body, ''), (
               SELECT string_agg(a.file_name, ', ' ORDER BY a.created_at, a.id)
               FROM issue_attachments a WHERE a.comment_id = c.id
           )), 300) AS preview
    FROM issue_comments c JOIN issues i ON i.id = c.issue_id
    WHERE c.deleted_at IS NULL
    UNION ALL
    SELECT c.id, c.author_id, c.created_at, c.parent_id IS NOT NULL,
           c.target_type, c.target_id::text,
           left(coalesce(nullif(c.body, ''), (
               SELECT string_agg(f.file_name, ', ' ORDER BY f.created_at, f.id)
               FROM content_files f WHERE f.comment_id = c.id
           )), 300)
    FROM content_comments c WHERE c.deleted_at IS NULL
    UNION ALL
    SELECT c.id, c.author_id, c.created_at, NULL::boolean,
           t.target_type, CASE WHEN t.target_type = 'ISSUE' THEN i.number::text ELSE t.target_id::text END,
           concat_ws(E'\n', CASE WHEN t.quote <> '' THEN '블록: ' || t.quote END, nullif(left(c.body, 300), ''))
    FROM content_thread_comments c JOIN content_threads t ON t.id = c.thread_id
    LEFT JOIN issues i ON t.target_type = 'ISSUE' AND i.id = t.target_id
    WHERE c.deleted_at IS NULL
), matches AS (
    SELECT n.id, min(s.id::text) AS comment_id
    FROM inbox_notifications n
    JOIN sources s ON (
        (s.target_type = 'ISSUE' AND substring(n.path FROM '^/issue/[A-Za-z][A-Za-z0-9]*-([1-9][0-9]*)$') = s.target_key)
        OR (s.target_type = 'DOCUMENT' AND n.path = '/doc/' || s.target_key)
        OR (s.target_type = 'PROJECT' AND n.path = '/project/' || s.target_key)
    )
    JOIN users u ON u.id = s.author_id
    WHERE position('#' IN n.path) = 0
      AND s.created_at <= n.created_at AND n.created_at <= s.created_at + interval '5 minutes'
      AND s.preview <> ''
      AND (n.body = s.preview OR starts_with(n.body, s.preview || E'\n언급: '))
      AND (
          (s.reply IS NOT TRUE AND n.headline = coalesce(nullif(u.display_name, ''), u.login_id) || ' 님이 댓글을 남겼습니다')
          OR (s.reply IS NOT FALSE AND n.headline = coalesce(nullif(u.display_name, ''), u.login_id) || ' 님이 댓글에 답글을 남겼습니다')
      )
    GROUP BY n.id HAVING count(*) = 1
)
UPDATE inbox_notifications n SET path = n.path || '#comment-' || m.comment_id
FROM matches m WHERE n.id = m.id;

-- Existing resolved conversations no longer need attention; read markers remain untouched.
WITH resolved_comments AS (
    SELECT c.id, root.resolved_at
    FROM issue_comments c JOIN issue_comments root ON root.id = coalesce(c.parent_id, c.id)
    WHERE root.resolved_at IS NOT NULL
    UNION ALL
    SELECT c.id, root.resolved_at
    FROM content_comments c JOIN content_comments root ON root.id = coalesce(c.parent_id, c.id)
    WHERE root.resolved_at IS NOT NULL
    UNION ALL
    SELECT c.id, t.resolved_at
    FROM content_thread_comments c JOIN content_threads t ON t.id = c.thread_id
    WHERE t.resolved_at IS NOT NULL
)
UPDATE inbox_notifications n SET completed_at = r.resolved_at
FROM resolved_comments r
WHERE n.actionable AND n.completed_at IS NULL AND split_part(n.path, '#comment-', 2) = r.id::text;
