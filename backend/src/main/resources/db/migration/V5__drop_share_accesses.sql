-- The record of when share links were opened is gone (docs/09-decisions.md D-208): it took a row for every opening
-- and was not worth the space. V3 and V4 stay as they were applied; this takes the table and its retention item away.
DROP TABLE share_accesses;

DELETE FROM retention_policies WHERE item = 'SHARE_ACCESSES';
ALTER TABLE retention_policies DROP CONSTRAINT retention_policies_item_check;
ALTER TABLE retention_policies
    ADD CONSTRAINT retention_policies_item_check CHECK (item IN ('ISSUES', 'DOCUMENTS', 'COMMENTS'));
