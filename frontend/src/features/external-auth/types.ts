/** GET/PUT /api/v1/admin/auth/ldap. The bind password is write-only. */
export type LdapSettings = {
  enabled: boolean;
  url: string;
  startTls: boolean;
  bindDn: string;
  bindPasswordSet: boolean;
  baseDn: string;
  userFilter: string;
  loginIdAttribute: string;
  emailAttribute: string;
  displayNameAttribute: string;
  autoProvision: boolean;
};

/** What is sent to save or test. A null password keeps the stored one. */
export type LdapSettingsInput = Omit<LdapSettings, "bindPasswordSet"> & {
  bindPassword: string | null;
  testLoginId?: string | null;
};

export type LdapTestResult = {
  ok: boolean;
  message: string;
  loginId: string | null;
  email: string | null;
  displayName: string | null;
};

/** GET/PUT /api/v1/admin/auth/sso. The client secret is write-only. */
export type SsoSettings = {
  enabled: boolean;
  displayName: string;
  issuer: string;
  clientId: string;
  clientSecretSet: boolean;
  scopes: string;
  loginIdClaim: string;
  autoProvision: boolean;
  /** OIDC reads everything from the issuer; OAUTH2 has its addresses and exchange spelled out. */
  protocol: "OIDC" | "OAUTH2";
  authorizeUrl: string;
  tokenUrl: string;
  userinfoUrl: string;
  /** How the client secret reaches the token address. */
  tokenAuth: "BASIC" | "BEARER" | "BODY";
  tokenFormat: "FORM" | "JSON";
  tokenBody: string;
  subjectField: string;
  emailField: string;
  nameField: string;
  buttonStyle: "DEFAULT" | "NAVER_WORKS";
  /** The address to register at the identity provider. */
  redirectUri: string;
};

export type SsoSettingsInput = Omit<
  SsoSettings,
  "clientSecretSet" | "redirectUri"
> & {
  clientSecret: string | null;
};
