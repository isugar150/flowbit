ALTER TABLE issues ADD COLUMN triage_rejected_at timestamptz;

-- Historic requests have no reliable rejection time. Start their retention period on upgrade.
UPDATE issues SET triage_rejected_at = CURRENT_TIMESTAMP WHERE triage_rejected;

CREATE INDEX issues_rejected_retention_idx ON issues (triage_rejected_at)
    WHERE triage_rejected AND NOT triage;

ALTER TABLE retention_policies DROP CONSTRAINT retention_policies_item_check;
ALTER TABLE retention_policies ADD CONSTRAINT retention_policies_item_check
    CHECK (item IN ('ISSUES', 'DOCUMENTS', 'COMMENTS', 'REJECTED_REQUESTS'));
