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
