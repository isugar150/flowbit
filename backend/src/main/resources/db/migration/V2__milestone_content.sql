-- A milestone ("목표") gets a document of its own, written in the block editor like a project's
-- overview: where the stage stands, what is left, what was decided
-- (docs/features/M3-projects-and-issues.md "목표의 상세").
ALTER TABLE milestones ADD COLUMN content jsonb;
