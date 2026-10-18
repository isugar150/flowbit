-- A milestone's own status (D-161), the same values as a project's. Completed and canceled ones are
-- folded away in the lists. Until now a milestone counted as finished when it had issues and all of
-- them were done (D-136); those start as COMPLETED, ones with work begun as IN_PROGRESS.
ALTER TABLE milestones
    ADD COLUMN status text NOT NULL DEFAULT 'PLANNED'
        CHECK (status IN ('BACKLOG', 'PLANNED', 'IN_PROGRESS', 'COMPLETED', 'CANCELED'));

UPDATE milestones m
SET status = counted.status
FROM (
    SELECT ms.id,
           CASE
               WHEN count(i.id) FILTER (WHERE i.status <> 'CANCELED') > 0
                    AND count(i.id) FILTER (WHERE i.status <> 'CANCELED') = count(i.id) FILTER (WHERE i.status = 'DONE')
                   THEN 'COMPLETED'
               WHEN count(i.id) FILTER (WHERE i.status IN ('IN_PROGRESS', 'DONE')) > 0 THEN 'IN_PROGRESS'
               ELSE 'PLANNED'
           END AS status
    FROM milestones ms
    LEFT JOIN issues i ON i.milestone_id = ms.id AND i.deleted_at IS NULL
    GROUP BY ms.id
) counted
WHERE counted.id = m.id;
