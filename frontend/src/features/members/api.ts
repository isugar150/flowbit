import { apiFetch } from "@/lib/api/client";
import type { Member, MemberStatus, WorkspaceRole } from "./types";

export function createMember(input: {
  loginId: string;
  password: string;
  displayName: string;
  role: WorkspaceRole;
}): Promise<Member> {
  return apiFetch<Member>("/api/v1/admin/members", { method: "POST", body: input });
}

export function updateMember(input: { id: string; role?: WorkspaceRole; status?: MemberStatus }): Promise<Member> {
  const { id, ...changes } = input;
  return apiFetch<Member>(`/api/v1/admin/members/${id}`, { method: "PATCH", body: changes });
}

export function resetMemberPassword(input: { id: string; password: string }): Promise<void> {
  return apiFetch<void>(`/api/v1/admin/members/${input.id}/password`, {
    method: "PUT",
    body: { password: input.password },
  });
}
