import { apiFetch } from "@/lib/api/client";
import type { Milestone } from "./types";

export function createMilestone(input: { projectKey: string; name: string; targetDate?: string | null }): Promise<Milestone> {
  return apiFetch<Milestone>(`/api/v1/projects/${input.projectKey}/milestones`, {
    method: "POST",
    body: { name: input.name, targetDate: input.targetDate ?? null },
  });
}

/** Only the fields present are changed; a null targetDate clears it. */
export function updateMilestone(input: {
  projectKey: string;
  id: string;
  changes: Partial<{ name: string; targetDate: string | null }>;
}): Promise<Milestone> {
  return apiFetch<Milestone>(`/api/v1/projects/${input.projectKey}/milestones/${input.id}`, {
    method: "PATCH",
    body: input.changes,
  });
}

/** The milestone's issues stay in the project, with no milestone. */
export function deleteMilestone(input: { projectKey: string; id: string }): Promise<void> {
  return apiFetch<void>(`/api/v1/projects/${input.projectKey}/milestones/${input.id}`, { method: "DELETE" });
}
