-- Labels shared by the whole workspace and put on issues (several per issue). They replace the
-- issue preset as the way to say what kind of issue this is. Anyone can add a label; the three
-- below are there from the start.
CREATE TABLE labels (
    id         uuid        PRIMARY KEY,
    name       text        NOT NULL CHECK (char_length(name) BETWEEN 1 AND 30),
    color      text        NOT NULL CHECK (color ~ '^#[0-9a-f]{6}$'),
    created_at timestamptz NOT NULL DEFAULT now()
);

-- "Bug" and "bug" are the same label.
CREATE UNIQUE INDEX labels_name_key ON labels (lower(name));

CREATE TABLE issue_labels (
    issue_id uuid NOT NULL REFERENCES issues (id) ON DELETE CASCADE,
    label_id uuid NOT NULL REFERENCES labels (id) ON DELETE CASCADE,
    PRIMARY KEY (issue_id, label_id)
);

CREATE INDEX issue_labels_label_id_idx ON issue_labels (label_id);

-- Labels are listed oldest first, so the times set the order: 버그, 기능, 중요.
INSERT INTO labels (id, name, color, created_at) VALUES
    (gen_random_uuid(), '버그', '#e5484d', now() - interval '2 seconds'),
    (gen_random_uuid(), '기능', '#8e6ee0', now() - interval '1 second'),
    (gen_random_uuid(), '중요', '#f2994a', now());

-- Issues that had a preset keep its meaning as a label.
INSERT INTO issue_labels (issue_id, label_id)
SELECT i.id, l.id FROM issues i JOIN labels l ON l.name = CASE i.preset WHEN 'BUG' THEN '버그' WHEN 'FEATURE' THEN '기능' END;
