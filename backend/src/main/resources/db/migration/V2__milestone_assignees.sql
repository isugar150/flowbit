-- A milestone can have several assignees, as an issue does (D-125, D-185). created_at keeps the
-- order they were given the milestone in, which is the order shown.
CREATE TABLE milestone_assignees (
    milestone_id uuid        NOT NULL REFERENCES milestones (id) ON DELETE CASCADE,
    user_id      uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at   timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (milestone_id, user_id)
);

CREATE INDEX milestone_assignees_user_id_idx ON milestone_assignees (user_id);
