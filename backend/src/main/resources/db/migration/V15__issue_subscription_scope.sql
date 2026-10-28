ALTER TABLE issue_subscriptions ADD COLUMN include_descendants boolean NOT NULL DEFAULT false;
ALTER TABLE issue_subscriptions ADD COLUMN excluded boolean NOT NULL DEFAULT false;
