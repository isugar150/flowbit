-- Single sign-on with providers that are not OpenID Connect, and webhooks that call an API once
-- per recipient (docs/features/M2b-ldap-and-sso.md "직접 설정", M7a-notification-channels.md "받는 사람마다").

-- OIDC: everything is read from the issuer's configuration document.
-- OAUTH2: the addresses and the shape of the code exchange are spelled out below. Covers plain
-- OAuth 2.0 (token, then a user-info call) and in-house providers that answer the code exchange
-- with the person's profile.
ALTER TABLE sso_settings ADD COLUMN protocol text NOT NULL DEFAULT 'OIDC' CHECK (protocol IN ('OIDC', 'OAUTH2'));
ALTER TABLE sso_settings ADD COLUMN authorize_url text NOT NULL DEFAULT '';
ALTER TABLE sso_settings ADD COLUMN token_url text NOT NULL DEFAULT '';
-- Empty: the answer of the code exchange is the profile.
ALTER TABLE sso_settings ADD COLUMN userinfo_url text NOT NULL DEFAULT '';
-- How the client secret reaches the token address.
ALTER TABLE sso_settings ADD COLUMN token_auth text NOT NULL DEFAULT 'BASIC' CHECK (token_auth IN ('BASIC', 'BEARER', 'BODY'));
ALTER TABLE sso_settings ADD COLUMN token_format text NOT NULL DEFAULT 'FORM' CHECK (token_format IN ('FORM', 'JSON'));
-- Empty: the standard OAuth 2.0 parameters. Otherwise a body with {{placeholders}}.
ALTER TABLE sso_settings ADD COLUMN token_body text NOT NULL DEFAULT '';
-- Where in the profile the values are; a dot goes into a nested object (kakao_account.email).
ALTER TABLE sso_settings ADD COLUMN subject_field text NOT NULL DEFAULT 'sub';
ALTER TABLE sso_settings ADD COLUMN email_field text NOT NULL DEFAULT 'email';
ALTER TABLE sso_settings ADD COLUMN name_field text NOT NULL DEFAULT 'name';
-- How the button on the sign-in page looks.
ALTER TABLE sso_settings ADD COLUMN button_style text NOT NULL DEFAULT 'DEFAULT' CHECK (button_style IN ('DEFAULT', 'NAVER_WORKS'));

ALTER TABLE webhooks DROP CONSTRAINT webhooks_method_check;
ALTER TABLE webhooks ADD CONSTRAINT webhooks_method_check CHECK (method IN ('GET', 'POST', 'PUT', 'PATCH', 'DELETE'));
-- Call the address once for each subscriber instead of once for the change.
ALTER TABLE webhooks ADD COLUMN per_recipient boolean NOT NULL DEFAULT false;
