-- Triage: in a project that uses it, an issue made by someone who does not manage the project waits in the triage queue until a
-- person who does accepts it, declines it or marks it a duplicate (D-231). The issue is a real issue meanwhile; the flag is what
-- puts it in the queue.
ALTER TABLE projects ADD COLUMN triage_enabled boolean NOT NULL DEFAULT false;

ALTER TABLE issues ADD COLUMN triage boolean NOT NULL DEFAULT false;

CREATE INDEX issues_triage_idx ON issues (project_id) WHERE triage AND deleted_at IS NULL;
