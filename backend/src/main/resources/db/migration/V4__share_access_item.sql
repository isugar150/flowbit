-- What was opened at a share link when it holds more than one page (docs/features/sharing.md, D-200): a shared
-- issue leads to its sub-issues and its documents, and each of those opened is a line of its own here. `item` is
-- `issue:13` or `document:<id>`; null for the link's own page (which is all the other kinds have).
ALTER TABLE share_accesses ADD COLUMN item text;
