create table personal_access_tokens (
    id uuid primary key,
    user_id uuid not null references users(id) on delete cascade,
    name text not null check (char_length(name) between 1 and 50),
    token_hash text not null unique,
    scope text not null check (scope in ('READ', 'WRITE')),
    created_at timestamptz not null,
    updated_at timestamptz not null,
    expires_at timestamptz not null,
    last_used_at timestamptz
);
create index personal_access_tokens_user_id_idx on personal_access_tokens(user_id);
