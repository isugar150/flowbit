-- A Git connection no longer limits which projects it reaches (D-149): each project chooses its
-- repositories in its own settings (project_git_repositories, D-148). A project without any takes
-- events from every repository, as "모든 프로젝트" did.
DROP TABLE git_connection_projects;
ALTER TABLE git_connections DROP COLUMN all_projects;
