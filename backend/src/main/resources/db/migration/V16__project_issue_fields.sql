CREATE TABLE project_issue_fields (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    label text NOT NULL CHECK (char_length(label) BETWEEN 1 AND 30),
    type text NOT NULL CHECK (type IN ('TEXT','NUMBER','DATE','CHECKBOX','LINK','PERSON')),
    default_value jsonb,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX project_issue_fields_label_key ON project_issue_fields(project_id, lower(label));
CREATE TABLE issue_field_values (
    issue_id uuid NOT NULL REFERENCES issues(id) ON DELETE CASCADE,
    field_id uuid NOT NULL REFERENCES project_issue_fields(id) ON DELETE CASCADE,
    value jsonb,
    PRIMARY KEY (issue_id, field_id)
);
CREATE INDEX issue_field_values_field_idx ON issue_field_values(field_id);
