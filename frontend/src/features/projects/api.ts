import type { RichDoc } from "@/components/editor/rich-text-editor";
import { apiFetch } from "@/lib/api/client";
import type { Project, ProjectMember, ProjectRole } from "./types";

export function createProject(input: { name: string; key: string; description: string }): Promise<Project> {
  return apiFetch<Project>("/api/v1/projects", { method: "POST", body: input });
}

/** Only the fields present are changed. Renaming needs the manager role; the rest needs edit. */
export function updateProject(input: {
  key: string;
  changes: Partial<{ name: string; description: string; content: RichDoc | null }>;
}): Promise<Project> {
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
