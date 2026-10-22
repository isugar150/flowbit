-- Properties people add to one project, milestone or issue on their own (D-186): a label, a kind of
-- value and the value. They belong to the one item they were added to (no shared definition), and go
-- with it when it is deleted for good. Kept apart from the item's own columns so the three kinds of
-- item share one table; exactly one of the three ids is set.
CREATE TABLE custom_properties (
    id           uuid        PRIMARY KEY,
    project_id   uuid        REFERENCES projects (id) ON DELETE CASCADE,
    milestone_id uuid        REFERENCES milestones (id) ON DELETE CASCADE,
    issue_id     uuid        REFERENCES issues (id) ON DELETE CASCADE,
    label        text        NOT NULL CHECK (char_length(label) BETWEEN 1 AND 30),
    type         text        NOT NULL CHECK (type IN ('TEXT', 'NUMBER', 'DATE', 'CHECKBOX', 'LINK', 'PERSON')),
    -- null: no value yet. TEXT and LINK a string, NUMBER a number, DATE 'YYYY-MM-DD', CHECKBOX a boolean,
    -- PERSON the user's id as a string.
    value        jsonb,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    CHECK (num_nonnulls(project_id, milestone_id, issue_id) = 1)
);

-- One label once per item, ignoring case.
CREATE UNIQUE INDEX custom_properties_project_label_idx ON custom_properties (project_id, lower(label)) WHERE project_id IS NOT NULL;
CREATE UNIQUE INDEX custom_properties_milestone_label_idx ON custom_properties (milestone_id, lower(label)) WHERE milestone_id IS NOT NULL;
CREATE UNIQUE INDEX custom_properties_issue_label_idx ON custom_properties (issue_id, lower(label)) WHERE issue_id IS NOT NULL;
