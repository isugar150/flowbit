-- Stages inside a project (docs/features/M3-projects-and-issues.md, "마일스톤"). Issues are
-- grouped into them; a milestone's progress is the share of its issues that are done.
CREATE TABLE milestones (
    id          uuid        PRIMARY KEY,
    project_id  uuid        NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    name        text        NOT NULL CHECK (char_length(name) BETWEEN 1 AND 80),
    target_date date,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX milestones_project_id_idx ON milestones (project_id);

-- Deleting a milestone keeps its issues; they just belong to no milestone.
ALTER TABLE issues ADD COLUMN milestone_id uuid REFERENCES milestones (id) ON DELETE SET NULL;

CREATE INDEX issues_milestone_id_idx ON issues (milestone_id);
