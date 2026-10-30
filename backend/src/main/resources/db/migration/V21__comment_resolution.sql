alter table issue_comments
    add column resolved_at timestamptz,
    add column resolved_by uuid references users(id),
    add constraint issue_comments_resolution_check check (
        (resolved_at is null and resolved_by is null) or
        (resolved_at is not null and resolved_by is not null and parent_id is null)
    );

alter table content_comments
    add column resolved_at timestamptz,
    add column resolved_by uuid references users(id),
    add constraint content_comments_resolution_check check (
        (resolved_at is null and resolved_by is null) or
        (resolved_at is not null and resolved_by is not null and parent_id is null)
    );

create index issue_comments_resolved_by_idx on issue_comments(resolved_by) where resolved_by is not null;
create index content_comments_resolved_by_idx on content_comments(resolved_by) where resolved_by is not null;
