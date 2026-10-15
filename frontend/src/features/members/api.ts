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
