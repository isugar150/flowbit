import { apiFetch, fileForm } from "@/lib/api/client";
import type { Me } from "@/features/auth/types";
import type { Workspace } from "./types";

export function updateDisplayName(displayName: string): Promise<Me> {
  return apiFetch<Me>("/api/v1/me", { method: "PATCH", body: { displayName } });
}

export function uploadAvatar(file: File): Promise<Me> {
  return apiFetch<Me>("/api/v1/me/avatar", { method: "PUT", body: fileForm(file) });
}

export function removeAvatar(): Promise<Me> {
  return apiFetch<Me>("/api/v1/me/avatar", { method: "DELETE" });
}

/** Changes the signed-in person's own password; other browsers are signed out. */
export function changePassword(input: { currentPassword: string; newPassword: string }): Promise<void> {
  return apiFetch<void>("/api/v1/me/password", { method: "PUT", body: input });
}

export function renameWorkspace(name: string): Promise<Workspace> {
  return apiFetch<Workspace>("/api/v1/admin/workspace", { method: "PATCH", body: { name } });
}

export function uploadWorkspaceLogo(file: File): Promise<Workspace> {
  return apiFetch<Workspace>("/api/v1/admin/workspace/logo", { method: "PUT", body: fileForm(file) });
}

export function removeWorkspaceLogo(): Promise<Workspace> {
  return apiFetch<Workspace>("/api/v1/admin/workspace/logo", { method: "DELETE" });
}

/** Changes the prefix of every issue key (FB-12 → NEW-12). */
export function setWorkspaceKey(workspaceKey: string): Promise<Workspace> {
  return apiFetch<Workspace>("/api/v1/admin/workspace", { method: "PATCH", body: { workspaceKey } });
}
