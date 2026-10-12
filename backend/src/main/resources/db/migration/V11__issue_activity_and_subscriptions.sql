-- What happened to an issue, in order: created, status changed, assigned … (the "활동" list on
-- the issue). `detail` holds the old and new values as they were named at the time, so the list
-- still reads right after a person, label or milestone is renamed or removed.
CREATE TABLE issue_activities (
    id         uuid        PRIMARY KEY,
    -- Order of recording; timestamps can tie.
    seq        bigint      GENERATED ALWAYS AS IDENTITY,
    issue_id   uuid        NOT NULL REFERENCES issues (id) ON DELETE CASCADE,
    actor_id   uuid        NOT NULL REFERENCES users (id),
    type       text        NOT NULL,
    detail     jsonb       NOT NULL DEFAULT '{}',
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX issue_activities_issue_id_idx ON issue_activities (issue_id, seq);

-- Who wants to hear about an issue. The author and the assignee are added automatically; anyone
-- who can see the issue may add or remove themselves. Notifications (mail, push) go to these people.
CREATE TABLE issue_subscriptions (
    issue_id   uuid        NOT NULL REFERENCES issues (id) ON DELETE CASCADE,
    user_id    uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (issue_id, user_id)
);

CREATE INDEX issue_subscriptions_user_id_idx ON issue_subscriptions (user_id);

-- Issues that already exist: a "created" entry, and their author and assignee subscribed.
INSERT INTO issue_activities (id, issue_id, actor_id, type, created_at)
SELECT gen_random_uuid(), id, created_by, 'CREATED', created_at FROM issues ORDER BY created_at;

INSERT INTO issue_subscriptions (issue_id, user_id)
SELECT id, created_by FROM issues
UNION
SELECT id, assignee_id FROM issues WHERE assignee_id IS NOT NULL;
