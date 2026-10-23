-- Share links (docs/features/sharing.md, D-197): a read-only page anyone with the address can open
-- without signing in. A link is one person's (`owner_id`) share of one kind of page (`kind`) with the
-- parameters that pick what of it is shown (`target`: the parameters sorted by name, `month=2026-10&scope=assigned`),
-- so the same person sharing the same thing again gets the same link. `token` is what the address holds,
-- a random value. A link that is stopped keeps its row (`revoked_at`) and never opens again; sharing the
-- same thing again after that makes a new link with a new token. `kind` is not constrained here: the
-- kinds are the code's to add.
CREATE TABLE share_links (
    id         uuid        PRIMARY KEY,
    token      text        NOT NULL UNIQUE,
    owner_id   uuid        NOT NULL REFERENCES users (id),
    kind       text        NOT NULL,
    target     text        NOT NULL,
    created_at timestamptz NOT NULL,
    revoked_at timestamptz
);

CREATE UNIQUE INDEX share_links_live_target_idx ON share_links (owner_id, kind, target) WHERE revoked_at IS NULL;

-- Every time a link was opened: when, from where, and, in a frame, on which page. Removed after the period
-- an administrator sets in the data retention settings (item SHARE_ACCESSES, D-198).
CREATE TABLE share_accesses (
    id          uuid        PRIMARY KEY,
    share_id    uuid        NOT NULL REFERENCES share_links (id) ON DELETE CASCADE,
    accessed_at timestamptz NOT NULL,
    ip_address  text,
    user_agent  text,
    embedded    boolean     NOT NULL DEFAULT false,
    referrer    text
);

CREATE INDEX share_accesses_share_accessed_at_idx ON share_accesses (share_id, accessed_at DESC);
CREATE INDEX share_accesses_accessed_at_idx ON share_accesses (accessed_at);

ALTER TABLE retention_policies DROP CONSTRAINT retention_policies_item_check;
ALTER TABLE retention_policies
    ADD CONSTRAINT retention_policies_item_check CHECK (item IN ('ISSUES', 'DOCUMENTS', 'COMMENTS', 'SHARE_ACCESSES'));
