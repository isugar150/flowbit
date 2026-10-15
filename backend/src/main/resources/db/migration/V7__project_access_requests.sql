-- Someone who may not enter a project asks to be let in (D-128). Only waiting requests are kept:
-- approving or turning one down removes the row.
CREATE TABLE project_access_requests (
    project_id uuid        NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    user_id    uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (project_id, user_id)
);

CREATE INDEX project_access_requests_user_id_idx ON project_access_requests (user_id);
