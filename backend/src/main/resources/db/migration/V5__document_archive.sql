-- Deleting a document archives it instead of removing the row (D-121): it leaves the lists, can
-- still be opened read-only, and can be restored. Who archived it and when is kept here.
ALTER TABLE documents
    ADD COLUMN deleted_at timestamptz,
    ADD COLUMN deleted_by uuid REFERENCES users (id) ON DELETE SET NULL;

CREATE INDEX documents_deleted_by_idx ON documents (deleted_by) WHERE deleted_by IS NOT NULL;
