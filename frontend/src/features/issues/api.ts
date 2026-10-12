import { apiFetch } from "@/lib/api/client";
import type { Issue, IssueChanges, NewIssueInput } from "./types";

export function createIssue(input: { projectKey: string; issue: NewIssueInput }): Promise<Issue> {
  return apiFetch<Issue>(`/api/v1/projects/${input.projectKey}/issues`, { method: "POST", body: input.issue });
}

export function updateIssue(input: { projectKey: string; number: number; changes: IssueChanges }): Promise<Issue> {
  return apiFetch<Issue>(`/api/v1/projects/${input.projectKey}/issues/${input.number}`, {
    method: "PATCH",
    body: input.changes,
  });
}
