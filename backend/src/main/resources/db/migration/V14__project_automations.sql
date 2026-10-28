CREATE TABLE project_automations (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    name text NOT NULL CHECK (char_length(name) BETWEEN 1 AND 80),
    enabled boolean NOT NULL DEFAULT true,
    from_status_id uuid REFERENCES issue_statuses(id) ON DELETE CASCADE,
    to_status_id uuid NOT NULL REFERENCES issue_statuses(id) ON DELETE CASCADE,
    actions jsonb NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);
CREATE INDEX project_automations_project_idx ON project_automations(project_id, created_at, id);
CREATE TABLE automation_runs (
    id uuid PRIMARY KEY,
    rule_id uuid,
    project_id uuid NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    issue_id uuid NOT NULL REFERENCES issues(id) ON DELETE CASCADE,
    issue_version integer NOT NULL,
    rule_name text NOT NULL,
    results jsonb NOT NULL,
    created_at timestamptz NOT NULL,
    UNIQUE (rule_id, issue_id, issue_version)
);
CREATE INDEX automation_runs_project_idx ON automation_runs(project_id, created_at DESC);
