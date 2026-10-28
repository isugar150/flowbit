import { apiFetch } from "@/lib/api/client";
import type { CycleSettings, ProjectCycles } from "./types";

/** The cycles of a project; the server makes the one of today first if it is missing. */
export function getCycles(projectId: string): Promise<ProjectCycles> {
  return apiFetch<ProjectCycles>(`/api/v1/projects/${projectId}/cycles`);
}

export function saveCycleSettings(input: { projectId: string; settings: CycleSettings }): Promise<ProjectCycles> {
  return apiFetch<ProjectCycles>(`/api/v1/projects/${input.projectId}/cycles/settings`, { method: "PUT", body: input.settings });
}

/** "10-26 ~ 11-08": the days of a cycle as the screens show them. */
export function cycleSpan(cycle: { startsOn: string; endsOn: string }): string {
  return `${cycle.startsOn.slice(5)} ~ ${cycle.endsOn.slice(5)}`;
}

/** How many days are left in a cycle today, counting today; 0 once it is over. */
export function daysLeft(cycle: { endsOn: string }, today: string): number {
  const ms = Date.parse(cycle.endsOn) - Date.parse(today);
  return Math.max(0, Math.round(ms / 86_400_000) + 1);
}
