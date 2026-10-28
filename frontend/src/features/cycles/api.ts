import { apiFetch } from "@/lib/api/client";
import { projectPath, type ProjectApiScope } from "@/features/projects/scope";
import type { CycleSettings, ProjectCycles } from "./types";

/** The cycles of a project; the server makes the one of today first if it is missing. */
export function getCycles(projectId: string, scope?: ProjectApiScope): Promise<ProjectCycles> {
  return apiFetch<ProjectCycles>(`${projectPath(projectId, scope)}/cycles`);
}

/** `scope` "admin": from 관리자 설정 > 프로젝트, for any project in use (D-236). */
export function saveCycleSettings(input: { projectId: string; settings: CycleSettings; scope?: ProjectApiScope }): Promise<ProjectCycles> {
  return apiFetch<ProjectCycles>(`${projectPath(input.projectId, input.scope)}/cycles/settings`, { method: "PUT", body: input.settings });
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
