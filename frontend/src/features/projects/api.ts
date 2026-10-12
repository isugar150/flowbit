import { apiFetch } from "@/lib/api/client";
import type { Project, ProjectChanges, ProjectHealth, ProjectMember, ProjectRole, ProjectUpdate } from "./types";

/** The project's key (its short name in addresses) is made by the server from the name. */
export function createProject(input: { name: string; description: string }): Promise<Project> {
  return apiFetch<Project>("/api/v1/projects", { method: "POST", body: input });
}

/** Only the fields present are changed. Renaming needs the manager role; the rest needs edit. */
export function updateProject(input: { key: string; changes: ProjectChanges }): Promise<Project> {
  return apiFetch<Project>(`/api/v1/projects/${input.key}`, { method: "PATCH", body: input.changes });
}

export function putProjectMember(input: { key: string; userId: string; role: ProjectRole }): Promise<ProjectMember[]> {
  return apiFetch<ProjectMember[]>(`/api/v1/projects/${input.key}/members/${input.userId}`, {
    method: "PUT",
    body: { role: input.role },
  });
}

export function removeProjectMember(input: { key: string; userId: string }): Promise<void> {
  return apiFetch<void>(`/api/v1/projects/${input.key}/members/${input.userId}`, { method: "DELETE" });
}

export function postProjectUpdate(input: {
  key: string;
  health: ProjectHealth;
  body: string;
}): Promise<ProjectUpdate> {
  return apiFetch<ProjectUpdate>(`/api/v1/projects/${input.key}/updates`, {
    method: "POST",
    body: { health: input.health, body: input.body },
  });
}

function fileForm(file: File): FormData {
  const form = new FormData();
  form.append("file", file);
  return form;
}

/** Managers only. The server converts the image to a 256px square WebP. */
export function uploadProjectIcon(key: string, file: File): Promise<Project> {
  return apiFetch<Project>(`/api/v1/projects/${key}/icon`, { method: "PUT", body: fileForm(file) });
}

export function removeProjectIcon(key: string): Promise<Project> {
  return apiFetch<Project>(`/api/v1/projects/${key}/icon`, { method: "DELETE" });
}
