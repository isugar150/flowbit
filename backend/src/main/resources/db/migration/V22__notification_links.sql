create table notification_links (
    token_hash text primary key check (token_hash ~ '^[0-9a-f]{64}$'),
    target_path text not null check (length(target_path) between 1 and 512),
    created_at timestamptz not null,
    expires_at timestamptz not null,
    check (expires_at > created_at)
);
create index notification_links_expires_at_idx on notification_links(expires_at);
