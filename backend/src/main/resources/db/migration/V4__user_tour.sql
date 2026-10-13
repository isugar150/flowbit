-- When the person first finished or skipped the onboarding tour (docs/features/onboarding-tour.md,
-- D-120). Null means they have not seen it, so the app shows it the next time they open it.
ALTER TABLE users ADD COLUMN tour_completed_at timestamptz;
