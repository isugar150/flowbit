ALTER TABLE inbox_notifications ADD COLUMN completed_at timestamptz;
ALTER TABLE inbox_notifications ADD COLUMN actionable boolean NOT NULL DEFAULT true;

-- Old notifications did not store event types. Recognize the unambiguous single status-change line.
UPDATE inbox_notifications SET actionable = false
    WHERE body LIKE '상태:%' AND position(E'\n' IN body) = 0;

ALTER TABLE inbox_notifications ADD CONSTRAINT inbox_completion_actionable
    CHECK (actionable OR completed_at IS NULL);

CREATE INDEX inbox_notifications_completion_idx
    ON inbox_notifications (user_id, (completed_at IS NOT NULL), created_at DESC, id DESC)
    WHERE actionable;
