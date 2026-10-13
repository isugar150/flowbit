import { apiFetch } from "@/lib/api/client";
import type { RichDoc } from "@/components/editor/rich-text-editor";
import type { Milestone } from "./types";

export function createMilestone(input: { projectId: string; name: string; targetDate?: string | null }): Promise<Milestone> {
  return apiFetch<Milestone>(`/api/v1/projects/${input.projectId}/milestones`, {
    method: "POST",
    body: { name: input.name, targetDate: input.targetDate ?? null },
  });
}

/** Only the fields present are changed; a null targetDate or content clears it. */
export function updateMilestone(input: {
  projectId: string;
  id: string;
  changes: Partial<{ name: string; targetDate: string | null; content: RichDoc | null }>;
}): Promise<Milestone> {
  return apiFetch<Milestone>(`/api/v1/projects/${input.projectId}/milestones/${input.id}`, {
    method: "PATCH",
    body: input.changes,
  });
}

/** The milestone's issues stay in the project, with no milestone. */
export function deleteMilestone(input: { projectId: string; id: string }): Promise<void> {
  return apiFetch<void>(`/api/v1/projects/${input.projectId}/milestones/${input.id}`, { method: "DELETE" });
}
