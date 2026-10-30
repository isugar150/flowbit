-- Period-based MCP evidence queries, including edits to older comments.
CREATE INDEX issue_activities_actor_time_idx ON issue_activities (actor_id, created_at, id);
CREATE INDEX issue_activities_time_idx ON issue_activities (created_at, id);
CREATE INDEX content_activities_actor_time_idx ON content_activities (actor_id, created_at, id);
CREATE INDEX content_activities_time_idx ON content_activities (created_at, id);
CREATE INDEX issue_comments_author_created_idx ON issue_comments (author_id, created_at, id) WHERE deleted_at IS NULL;
CREATE INDEX issue_comments_author_updated_idx ON issue_comments (author_id, updated_at, id) WHERE deleted_at IS NULL;
CREATE INDEX issue_comments_created_idx ON issue_comments (created_at, id) WHERE deleted_at IS NULL;
CREATE INDEX issue_comments_updated_idx ON issue_comments (updated_at, id) WHERE deleted_at IS NULL;
CREATE INDEX content_comments_author_created_idx ON content_comments (author_id, created_at, id) WHERE deleted_at IS NULL;
CREATE INDEX content_comments_author_updated_idx ON content_comments (author_id, updated_at, id) WHERE deleted_at IS NULL;
CREATE INDEX content_comments_created_idx ON content_comments (created_at, id) WHERE deleted_at IS NULL;
CREATE INDEX content_comments_updated_idx ON content_comments (updated_at, id) WHERE deleted_at IS NULL;
CREATE INDEX content_thread_comments_author_created_idx ON content_thread_comments (author_id, created_at, id) WHERE deleted_at IS NULL;
CREATE INDEX content_thread_comments_author_updated_idx ON content_thread_comments (author_id, updated_at, id) WHERE deleted_at IS NULL;
CREATE INDEX content_thread_comments_created_idx ON content_thread_comments (created_at, id) WHERE deleted_at IS NULL;
CREATE INDEX content_thread_comments_updated_idx ON content_thread_comments (updated_at, id) WHERE deleted_at IS NULL;
