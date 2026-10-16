-- What each Git stage moves an issue to, per connection (D-158), in place of the two switches
-- "pushing or opening starts the work" and "merging finishes it". A JSON object from stage
-- (BRANCH, PR_DRAFT, PR_OPEN, PR_MERGED, PR_CLOSED, CLOSING_COMMIT) to a status, or null for none.
ALTER TABLE git_connections ADD COLUMN status_rules text NOT NULL DEFAULT '{}';

UPDATE git_connections
SET status_rules = json_build_object(
    'BRANCH', CASE WHEN auto_in_progress THEN 'IN_PROGRESS' END,
    'PR_DRAFT', CASE WHEN auto_in_progress THEN 'IN_PROGRESS' END,
    'PR_OPEN', CASE WHEN auto_in_progress THEN 'IN_PROGRESS' END,
    'PR_MERGED', CASE WHEN auto_done THEN 'DONE' END,
    'PR_CLOSED', NULL,
    'CLOSING_COMMIT', CASE WHEN auto_done THEN 'DONE' END
)::text;

ALTER TABLE git_connections DROP COLUMN auto_in_progress;
ALTER TABLE git_connections DROP COLUMN auto_done;
