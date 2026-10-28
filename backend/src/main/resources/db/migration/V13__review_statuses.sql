-- Both review steps remain unfinished. Keep existing names/IDs and append them after ordinary work statuses.
INSERT INTO issue_statuses (id, category, name, position)
SELECT '00000000-0000-7000-8000-0000000000a6', 'IN_PROGRESS', '리뷰 준비됨',
       coalesce((SELECT max(position) FROM issue_statuses WHERE category = 'IN_PROGRESS'
                 AND lower(name) NOT IN (lower('리뷰 준비됨'), lower('리뷰 중'))), 10) + 10
ON CONFLICT (lower(name)) DO UPDATE SET category = excluded.category, position = excluded.position;
INSERT INTO issue_statuses (id, category, name, position)
SELECT '00000000-0000-7000-8000-0000000000a7', 'IN_PROGRESS', '리뷰 중',
       coalesce((SELECT max(position) FROM issue_statuses WHERE category = 'IN_PROGRESS'
                 AND lower(name) NOT IN (lower('리뷰 준비됨'), lower('리뷰 중'))), 10) + 20
ON CONFLICT (lower(name)) DO UPDATE SET category = excluded.category, position = excluded.position;
UPDATE issues i SET status = s.category FROM issue_statuses s
WHERE i.status_id = s.id AND i.status <> s.category;
