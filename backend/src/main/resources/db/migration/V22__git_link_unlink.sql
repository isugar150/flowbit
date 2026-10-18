-- A branch or pull request someone took off the issue (D-161). The row stays, marked, so that the
-- next push or event for it does not link it again; such links are not shown and do not move the
-- issue.
ALTER TABLE issue_git_links ADD COLUMN unlinked_at timestamptz;
