-- "Fixes" pushed straight to the default branch hands an issue on to the next status instead of finishing it (D-241), so that the
-- review steps between "진행 중" and "완료" get their turn. Connections stored the old default of that stage explicitly.
UPDATE git_connections
SET status_rules = replace(status_rules, '"CLOSING_COMMIT":"DONE"', '"CLOSING_COMMIT":"NEXT"')
WHERE status_rules LIKE '%"CLOSING_COMMIT":"DONE"%';
