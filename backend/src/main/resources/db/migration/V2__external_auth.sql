-- Sign-in through an LDAP directory or an OpenID Connect provider (docs/features/M2b-ldap-and-sso.md).

-- A login id is a plain id or an e-mail address. Ids that come from a directory keep their own
-- length, so the minimum of four characters is now enforced by the application for local accounts only.
ALTER TABLE users DROP CONSTRAINT users_login_id_check;
ALTER TABLE users ADD CONSTRAINT users_login_id_check CHECK (login_id ~ '^[a-z0-9][a-z0-9._@+-]{0,253}$');

-- Where the account's password is checked. Only local accounts have a password here.
ALTER TABLE users ADD COLUMN auth_source text NOT NULL DEFAULT 'LOCAL' CHECK (auth_source IN ('LOCAL', 'LDAP', 'SSO'));
-- The provider's own identifier for the person (OIDC `sub`); null for local and LDAP accounts.
ALTER TABLE users ADD COLUMN external_id text;
ALTER TABLE users ALTER COLUMN password_hash DROP NOT NULL;
ALTER TABLE users ADD CONSTRAINT users_password_hash_check CHECK ((auth_source = 'LOCAL') = (password_hash IS NOT NULL));
CREATE UNIQUE INDEX users_external_id_key ON users (auth_source, external_id) WHERE external_id IS NOT NULL;

-- One row each (id = 1), like instance_settings.
CREATE TABLE ldap_settings (
    id                     smallint    PRIMARY KEY CHECK (id = 1),
    enabled                boolean     NOT NULL DEFAULT false,
    -- ldap://host:389 or ldaps://host:636
    url                    text        NOT NULL DEFAULT '',
    start_tls              boolean     NOT NULL DEFAULT false,
    -- The account used to look people up; empty for an anonymous search.
    bind_dn                text        NOT NULL DEFAULT '',
    bind_password          text        NOT NULL DEFAULT '',
    base_dn                text        NOT NULL DEFAULT '',
    -- {0} is replaced by what the person typed as their id.
    user_filter            text        NOT NULL DEFAULT '(|(uid={0})(mail={0}))',
    login_id_attribute     text        NOT NULL DEFAULT 'uid',
    email_attribute        text        NOT NULL DEFAULT 'mail',
    display_name_attribute text        NOT NULL DEFAULT 'cn',
    -- Create the account at the first successful sign-in.
    auto_provision         boolean     NOT NULL DEFAULT true,
    updated_at             timestamptz NOT NULL DEFAULT now()
);

INSERT INTO ldap_settings (id) VALUES (1);

CREATE TABLE sso_settings (
    id             smallint    PRIMARY KEY CHECK (id = 1),
    enabled        boolean     NOT NULL DEFAULT false,
    -- Label of the button on the sign-in page.
    display_name   text        NOT NULL DEFAULT 'SSO' CHECK (char_length(display_name) BETWEEN 1 AND 40),
    issuer         text        NOT NULL DEFAULT '',
    client_id      text        NOT NULL DEFAULT '',
    client_secret  text        NOT NULL DEFAULT '',
    scopes         text        NOT NULL DEFAULT 'openid profile email',
    -- The claim that becomes the login id; `email` is used when it is missing.
    login_id_claim text        NOT NULL DEFAULT 'preferred_username',
    auto_provision boolean     NOT NULL DEFAULT true,
    updated_at     timestamptz NOT NULL DEFAULT now()
);

INSERT INTO sso_settings (id) VALUES (1);
