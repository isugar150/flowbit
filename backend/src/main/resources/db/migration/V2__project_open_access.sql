-- A project can be open to every member of the workspace (D-192). While it is, anyone who can sign in
-- enters it as an editor (a direct member keeps the higher of that and their own role) without having
-- a `project_members` row, so people who sign up later are let in too. Off by default: only the
-- people added to the project enter it, as before.
ALTER TABLE projects ADD COLUMN open_to_all boolean NOT NULL DEFAULT false;
