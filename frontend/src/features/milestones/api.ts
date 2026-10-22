import { apiFetch } from "@/lib/api/client";
import type { RichDoc } from "@/components/editor/rich-text-editor";
import type { Milestone, MilestoneChanges, MilestoneStatus } from "./types";

export function createMilestone(input: { projectId: string; name: string; targetDate?: string | null }): Promise<Milestone> {
  return apiFetch<Milestone>(`/api/v1/projects/${input.projectId}/milestones`, {
    method: "POST",
    body: { name: input.name, targetDate: input.targetDate ?? null },
  });
}

/** Only the fields present are changed; a null targetDate or content clears it, and `assigneeIds` is the whole list. */
export function updateMilestone(input: {
  projectId: string;
  id: string;
  changes: Partial<{ name: string; targetDate: string | null; status: MilestoneStatus; content: RichDoc | null; assigneeIds: string[] }>;
}): Promise<Milestone> {
  return apiFetch<Milestone>(`/api/v1/projects/${input.projectId}/milestones/${input.id}`, {
    method: "PATCH",
    body: input.changes,
  });
}

/** What the dialog changed, sent: the request names the assignees by their ids. */
export function saveMilestoneChanges(input: { projectId: string; id: string; changes: MilestoneChanges }): Promise<Milestone> {
  const { assignees, ...rest } = input.changes;
  return updateMilestone({ ...input, changes: assignees ? { ...rest, assigneeIds: assignees.map((person) => person.id) } : rest });
}

/** The milestone's issues stay in the project, with no milestone. */
export function deleteMilestone(input: { projectId: string; id: string }): Promise<void> {
  return apiFetch<void>(`/api/v1/projects/${input.projectId}/milestones/${input.id}`, { method: "DELETE" });
}
