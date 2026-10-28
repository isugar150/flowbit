import { apiFetch } from "@/lib/api/client";
import { projectPath, type ProjectApiScope } from "@/features/projects/scope";
import type { AutomationInput, AutomationRule, AutomationRun } from "./types";

export const automationPath = (projectId: string, scope: ProjectApiScope) => `${projectPath(projectId, scope)}/automations`;
export const getAutomations = (projectId: string, scope: ProjectApiScope) => apiFetch<AutomationRule[]>(automationPath(projectId, scope));
export const getAutomationRuns = (projectId: string, scope: ProjectApiScope) => apiFetch<AutomationRun[]>(`${automationPath(projectId, scope)}/runs`);
export const saveAutomation = (projectId: string, scope: ProjectApiScope, id: string | undefined, input: AutomationInput) =>
  apiFetch<AutomationRule>(`${automationPath(projectId, scope)}${id ? `/${id}` : ""}`, { method: id ? "PUT" : "POST", body: input });
export const deleteAutomation = (projectId: string, scope: ProjectApiScope, id: string) =>
  apiFetch<void>(`${automationPath(projectId, scope)}/${id}`, { method: "DELETE" });
