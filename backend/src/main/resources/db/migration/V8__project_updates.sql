-- Project updates (docs/features/M3-projects-and-issues.md, "프로젝트 업데이트와 건강도"): a short
-- note on how the project is going with a health value. The latest one is the project's health.
CREATE TABLE project_updates (
    id         uuid        PRIMARY KEY,
    project_id uuid        NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    author_id  uuid        NOT NULL REFERENCES users (id),
    health     text        NOT NULL CHECK (health IN ('ON_TRACK', 'AT_RISK', 'OFF_TRACK')),
    body       text        NOT NULL DEFAULT '' CHECK (char_length(body) <= 5000),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX project_updates_project_created_idx ON project_updates (project_id, created_at DESC);
