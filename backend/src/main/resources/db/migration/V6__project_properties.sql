-- Properties shown on the right of the project overview (docs/features/M3-projects-and-issues.md,
-- "프로젝트 속성"): where the project stands, how urgent it is, who leads it, when it runs, and tags.
ALTER TABLE projects
    ADD COLUMN status      text NOT NULL DEFAULT 'BACKLOG'
                           CHECK (status IN ('BACKLOG', 'PLANNED', 'IN_PROGRESS', 'COMPLETED', 'CANCELED')),
    ADD COLUMN priority    text NOT NULL DEFAULT 'NONE'
                           CHECK (priority IN ('NONE', 'URGENT', 'HIGH', 'MEDIUM', 'LOW')),
    ADD COLUMN lead_id     uuid REFERENCES users (id) ON DELETE SET NULL,
    ADD COLUMN start_date  date,
    ADD COLUMN target_date date,
    -- Free-form tags. Suggestions come from the labels of the other projects; there is no label table yet.
    ADD COLUMN labels      text[] NOT NULL DEFAULT '{}' CHECK (cardinality(labels) <= 10),
    ADD CONSTRAINT projects_dates_order CHECK (start_date IS NULL OR target_date IS NULL OR start_date <= target_date);
