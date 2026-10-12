import { apiFetch } from "@/lib/api/client";
import type { Me } from "@/features/auth/types";
import type { Workspace } from "./types";

function fileForm(file: File): FormData {
  const form = new FormData();
  form.append("file", file);
  return form;
}

export function updateDisplayName(displayName: string): Promise<Me> {
  return apiFetch<Me>("/api/v1/me", { method: "PATCH", body: { displayName } });
}

export function uploadAvatar(file: File): Promise<Me> {
  return apiFetch<Me>("/api/v1/me/avatar", { method: "PUT", body: fileForm(file) });
}

export function removeAvatar(): Promise<Me> {
  return apiFetch<Me>("/api/v1/me/avatar", { method: "DELETE" });
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
