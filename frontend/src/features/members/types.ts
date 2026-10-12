export type WorkspaceRole = "ADMIN" | "MEMBER";
export type MemberStatus = "ACTIVE" | "DISABLED";

/** A member as administrators see them (GET /api/v1/admin/members). */
export type Member = {
  id: string;
  loginId: string;
  displayName: string | null;
  avatarUrl: string | null;
  role: WorkspaceRole;
  status: MemberStatus;
  /** Where the password is checked. Only LOCAL accounts have one here to reset. */
  authSource: "LOCAL" | "LDAP" | "SSO";
  email: string | null;
  lastLoginAt: string | null;
  createdAt: string;
};

/** A person as any signed-in user sees them (GET /api/v1/users). */
export type UserSummary = {
  id: string;
  loginId: string;
  displayName: string | null;
  avatarUrl: string | null;
};
