-- The inbox (docs/features/inbox-and-daily-summary.md, D-216): every notification a person is told about is kept for them
-- to read in the app, so that what they have not read yet can be shown and counted.
CREATE TABLE inbox_notifications (
    id         uuid        PRIMARY KEY,
    user_id    uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    -- As the mail and push say it: "[FB-12] title", "김지민 님이 티켓을 수정했습니다", one line per change.
    title      text        NOT NULL,
    headline   text        NOT NULL,
    body       text        NOT NULL DEFAULT '',
    -- Where it opens in the app: /issue/FB-12.
    path       text        NOT NULL,
    created_at timestamptz NOT NULL,
    read_at    timestamptz
);

CREATE INDEX inbox_notifications_user_idx ON inbox_notifications (user_id, created_at DESC);
CREATE INDEX inbox_notifications_unread_idx ON inbox_notifications (user_id) WHERE read_at IS NULL;

-- The daily summary an administrator switches on for a member (D-216). No row: off.
CREATE TABLE member_daily_summaries (
    user_id      uuid    PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    enabled      boolean NOT NULL DEFAULT false,
    -- In the member's time zone (users.timezone, else the workspace's).
    send_time    time    NOT NULL DEFAULT '09:00',
    -- The member's own date it was last sent on, so that it goes out once a day.
    last_sent_on date
);
