import { apiFetch } from "@/lib/api/client";
import type { Member, MemberStatus, WorkspaceRole } from "./types";

export function createMember(input: {
  loginId: string;
  password: string;
  displayName: string;
  role: WorkspaceRole;
}): Promise<Member> {
  return apiFetch<Member>("/api/v1/admin/members", {
    method: "POST",
    body: input,
  });
}

export function updateMember(input: {
  id: string;
  role?: WorkspaceRole;
  status?: MemberStatus;
}): Promise<Member> {
  const { id, ...changes } = input;
  return apiFetch<Member>(`/api/v1/admin/members/${id}`, {
    method: "PATCH",
    body: changes,
  });
}

export function resetMemberPassword(input: {
  id: string;
  password: string;
}): Promise<void> {
  return apiFetch<void>(`/api/v1/admin/members/${input.id}/password`, {
    method: "PUT",
    body: { password: input.password },
  });
}

/** One project and what a member may do in it; `role` is null when they are not a member. */
export type MemberProjectRole = {
  projectId: string;
  name: string;
  role: "MANAGER" | "EDITOR" | "VIEWER" | null;
  /** The project is open to every member of the workspace: they enter as editors without a role (D-192). */
  openToAll: boolean;
};

/**
 * Sets one person's role in a project from the administrator screen, without the administrator
 * being a member of it (D-131). Null takes them out of the project.
 */
export function setMemberProjectRole(input: {
  memberId: string;
  projectId: string;
  role: "MANAGER" | "EDITOR" | "VIEWER" | null;
}): Promise<void> {
  const path = `/api/v1/admin/members/${input.memberId}/projects/${input.projectId}`;
  return input.role
    ? apiFetch<void>(path, { method: "PUT", body: { role: input.role } })
    : apiFetch<void>(path, { method: "DELETE" });
}

export function memberProjectRoles(
  memberId: string,
): Promise<MemberProjectRole[]> {
  return apiFetch<MemberProjectRole[]>(
    `/api/v1/admin/members/${memberId}/projects`,
  );
}

/** The daily summary an administrator set for a member (D-216). `time` is `09:00`, in the member's own time zone. */
export type MemberDailySummary = {
  enabled: boolean;
  time: string;
  timezone: string;
  /** The member's own date it was last sent on. */
  lastSentOn: string | null;
};

export function memberDailySummary(memberId: string): Promise<MemberDailySummary> {
  return apiFetch<MemberDailySummary>(`/api/v1/admin/members/${memberId}/daily-summary`);
}

export function saveMemberDailySummary(input: { memberId: string; enabled: boolean; time: string }): Promise<MemberDailySummary> {
  return apiFetch<MemberDailySummary>(`/api/v1/admin/members/${input.memberId}/daily-summary`, {
    method: "PUT",
    body: { enabled: input.enabled, time: input.time },
  });
}

/**
 * What sending the member's summary now came to (D-220). `hadContent` false: nothing to say, nothing sent. `sent`: it reached them by
 * some way; `mailed`, `pushed`, `apiReached`: how many of each; `apiFailed`: API notifications that did not answer well.
 */
export type MemberDailySummarySent = {
  hadContent: boolean;
  sent: boolean;
  mailed: number;
  pushed: number;
  apiReached: number;
  apiFailed: number;
};

/** Sends the member's summary now. */
export function sendMemberDailySummary(memberId: string): Promise<MemberDailySummarySent> {
  return apiFetch<MemberDailySummarySent>(`/api/v1/admin/members/${memberId}/daily-summary/send`, { method: "POST" });
}
