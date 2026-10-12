-- Documents written in the block editor, stored as the editor's JSON (validated by RichText).
-- projects.description stays as the one-line summary; issues.description holds the plain text
-- extracted from the document, for previews and search.
ALTER TABLE projects ADD COLUMN content jsonb;
ALTER TABLE issues ADD COLUMN content jsonb;
