/** Response of GET /api/v1/me, POST /api/v1/auth/login and POST /api/v1/setup. */
export type Me = {
  id: string;
  loginId: string;
  /** The name to show instead of the login id, when the person has set one. */
  displayName: string | null;
  avatarUrl: string | null;
  role: "ADMIN" | "MEMBER";
  locale: string;
  timezone: string;
};

export type Credentials = {
  loginId: string;
  password: string;
};

export type SetupRequest = Credentials & {
  workspaceName: string;
  workspaceKey: string;
};

/** Response of GET /api/v1/auth/methods: the ways of signing in the sign-in page offers. */
export type AuthMethods = {
  ldap: boolean;
  sso: { displayName: string; buttonStyle: "DEFAULT" | "NAVER_WORKS" } | null;
};
