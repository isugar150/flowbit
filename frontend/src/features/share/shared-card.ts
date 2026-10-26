import type { IssueCardData } from "@/features/issues/components/issue-card";
import type { SharedCalendarIssue } from "./types";

/**
 * The card over an issue on a shared calendar (D-201): what the issue is. No history (D-214): the server does not send
 * it, and the card has no "최근 활동" part.
 */
export function sharedCardOf(issue: SharedCalendarIssue): IssueCardData {
  return {
    key: issue.key,
    title: issue.title,
    status: issue.status,
    priority: issue.priority,
    startDate: issue.startDate,
    dueDate: issue.dueDate,
    project: issue.project,
    requester: issue.createdBy,
    labels: issue.labels,
  };
}
