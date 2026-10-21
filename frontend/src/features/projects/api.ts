import { apiFetch, fileForm } from "@/lib/api/client";
import type { Project, ProjectChanges, ProjectEntry, ProjectHealth, ProjectMember, ProjectRole, ProjectUpdate } from "./types";

export function createProject(input: { name: string }): Promise<Project> {
  return apiFetch<Project>("/api/v1/projects", { method: "POST", body: input });
}

/** Only the fields present are changed. Renaming needs the manager role; the rest needs edit. */
export function updateProject(input: { id: string; changes: ProjectChanges }): Promise<Project> {
  return apiFetch<Project>(`/api/v1/projects/${input.id}`, { method: "PATCH", body: input.changes });
}

/** Managers only. The project leaves the lists and cannot be entered until it is restored. */
export function archiveProject(id: string): Promise<void> {
  return apiFetch<void>(`/api/v1/projects/${id}/archive`, { method: "POST" });
}

export function restoreProject(id: string): Promise<Project> {
  return apiFetch<Project>(`/api/v1/projects/${id}/restore`, { method: "POST" });
}

export function putProjectMember(input: { id: string; userId: string; role: ProjectRole }): Promise<ProjectMember[]> {
  return apiFetch<ProjectMember[]>(`/api/v1/projects/${input.id}/members/${input.userId}`, {
    method: "PUT",
    body: { role: input.role },
  });
}

export function removeProjectMember(input: { id: string; userId: string }): Promise<void> {
  return apiFetch<void>(`/api/v1/projects/${input.id}/members/${input.userId}`, { method: "DELETE" });
}

export function postProjectUpdate(input: {
  id: string;
  health: ProjectHealth;
  body: string;
}): Promise<ProjectUpdate> {
  return apiFetch<ProjectUpdate>(`/api/v1/projects/${input.id}/updates`, {
    method: "POST",
    body: { health: input.health, body: input.body },
  });
}

/** The writer of the update or a manager of the project. */
export function deleteProjectUpdate(input: { id: string; updateId: string }): Promise<void> {
  return apiFetch<void>(`/api/v1/projects/${input.id}/updates/${input.updateId}`, { method: "DELETE" });
}

/** Managers only. The server converts the image to a 256px square WebP. */
export function uploadProjectIcon(id: string, file: File): Promise<Project> {
  return apiFetch<Project>(`/api/v1/projects/${id}/icon`, { method: "PUT", body: fileForm(file) });
}

export function removeProjectIcon(id: string): Promise<Project> {
  return apiFetch<Project>(`/api/v1/projects/${id}/icon`, { method: "DELETE" });
}

/** Asks the managers to let the signed-in user in (D-128). Asking again while waiting changes nothing. */
export function requestProjectAccess(id: string): Promise<ProjectEntry> {
  return apiFetch<ProjectEntry>(`/api/v1/projects/${id}/access-requests`, { method: "POST" });
}

/** Lets a waiting person in with this role. Managers only. */
export function approveProjectAccess(input: { id: string; userId: string; role: ProjectRole }): Promise<ProjectMember[]> {
  return apiFetch<ProjectMember[]>(`/api/v1/projects/${input.id}/access-requests/${input.userId}/approve`, {
    method: "POST",
    body: { role: input.role },
  });
}

/** Turns a request down; the person may ask again. Managers only. */
export function rejectProjectAccess(input: { id: string; userId: string }): Promise<void> {
  return apiFetch<void>(`/api/v1/projects/${input.id}/access-requests/${input.userId}`, { method: "DELETE" });
}
