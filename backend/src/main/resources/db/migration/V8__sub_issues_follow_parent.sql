-- A sub-issue is always in its parent's project and milestone (D-129). Before, one could be put
-- somewhere else on purpose; those are moved to where the issue at the top of their tree is.
-- No history entries are written for this.
WITH RECURSIVE tree AS (
    SELECT id, project_id, milestone_id FROM issues WHERE parent_id IS NULL
    UNION ALL
    SELECT child.id, tree.project_id, tree.milestone_id
    FROM issues child JOIN tree ON child.parent_id = tree.id
)
UPDATE issues i
SET project_id = tree.project_id, milestone_id = tree.milestone_id, version = i.version + 1
FROM tree
WHERE i.id = tree.id
  AND i.parent_id IS NOT NULL
  AND (i.project_id IS DISTINCT FROM tree.project_id OR i.milestone_id IS DISTINCT FROM tree.milestone_id);
