-- Ticket edits are informational. Keep read state and comment/create notifications intact.
UPDATE inbox_notifications
SET actionable = false, completed_at = null
WHERE path ~ '^/issue/[A-Za-z][A-Za-z0-9]*-[1-9][0-9]*(#.*)?$'
  AND path NOT LIKE '%#comment-%'
  AND (headline LIKE '% 님이 티켓을 수정했습니다'
       OR headline LIKE '%에서 티켓을 수정했습니다');
