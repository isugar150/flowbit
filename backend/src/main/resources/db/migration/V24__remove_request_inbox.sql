-- Release pending requests into the first backlog status; preserve all issue content and relations.
UPDATE issues
SET status = 'BACKLOG',
    status_id = (SELECT id FROM issue_statuses WHERE category = 'BACKLOG' ORDER BY position, name LIMIT 1),
    version = version + 1,
    updated_at = CURRENT_TIMESTAMP
WHERE triage;

-- Rejected requests keep their canceled status and content. They are ordinary tickets now.
DELETE FROM retention_policies WHERE item = 'REJECTED_REQUESTS';
ALTER TABLE retention_policies DROP CONSTRAINT retention_policies_item_check;
ALTER TABLE retention_policies ADD CONSTRAINT retention_policies_item_check CHECK (item IN ('ISSUES', 'DOCUMENTS', 'COMMENTS'));
ALTER TABLE issues DROP COLUMN triage, DROP COLUMN triage_rejected, DROP COLUMN triage_rejected_at;
ALTER TABLE projects DROP COLUMN triage_enabled;

-- Remove the retired event filter. An exclusive subscription must not become an all-events hook.
UPDATE webhooks
SET enabled = CASE WHEN array_remove(string_to_array(event_types, ','), 'TRIAGE_REQUESTED') = ARRAY[]::text[] THEN false ELSE enabled END,
    event_types = array_to_string(array_remove(string_to_array(event_types, ','), 'TRIAGE_REQUESTED'), ','),
    updated_at = CURRENT_TIMESTAMP
WHERE 'TRIAGE_REQUESTED' = ANY(string_to_array(event_types, ','));
