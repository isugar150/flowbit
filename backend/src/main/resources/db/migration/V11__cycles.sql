-- Cycles: a project's repeating work periods (sprints that run themselves). A project turns them on and says how many
-- weeks one lasts and on which weekday it starts; the next cycle is made by itself, and what is not finished when one ends
-- moves on to the next (D-232).
ALTER TABLE projects
    ADD COLUMN cycles_enabled   boolean  NOT NULL DEFAULT false,
    ADD COLUMN cycle_weeks      smallint NOT NULL DEFAULT 2 CHECK (cycle_weeks BETWEEN 1 AND 4),
    -- ISO weekday: 1 = Monday … 7 = Sunday
    ADD COLUMN cycle_start_day  smallint NOT NULL DEFAULT 1 CHECK (cycle_start_day BETWEEN 1 AND 7);

CREATE TABLE cycles (
    id         uuid        PRIMARY KEY,
    project_id uuid        NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    number     integer     NOT NULL,
    -- Both days belong to the cycle.
    starts_on  date        NOT NULL,
    ends_on    date        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT cycles_span CHECK (ends_on >= starts_on),
    CONSTRAINT cycles_number_key UNIQUE (project_id, number),
    CONSTRAINT cycles_start_key UNIQUE (project_id, starts_on)
);

ALTER TABLE issues ADD COLUMN cycle_id uuid REFERENCES cycles (id) ON DELETE SET NULL;

CREATE INDEX issues_cycle_idx ON issues (cycle_id) WHERE cycle_id IS NOT NULL;
