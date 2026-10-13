import type { Issue, IssuePriority } from "./types";

/** The orders a list of issues can be put in; `?sort=` in the address. The first is the default. */
export const ISSUE_SORTS = ["created", "updated", "priority", "due", "title"] as const;
export type IssueSort = (typeof ISSUE_SORTS)[number];
export const DEFAULT_ISSUE_SORT: IssueSort = "created";

const PRIORITY_RANK: Record<IssuePriority, number> = { URGENT: 0, HIGH: 1, MEDIUM: 2, LOW: 3, NONE: 4 };

const newestFirst = (a: Issue, b: Issue) => b.number - a.number;

const COMPARE: Record<IssueSort, (a: Issue, b: Issue) => number> = {
  created: newestFirst,
  updated: (a, b) => b.updatedAt.localeCompare(a.updatedAt),
  priority: (a, b) => PRIORITY_RANK[a.priority] - PRIORITY_RANK[b.priority],
  // The nearest date first; issues without one come last.
  due: (a, b) => (a.dueDate ?? "9999").localeCompare(b.dueDate ?? "9999"),
  title: (a, b) => a.title.localeCompare(b.title, "ko", { numeric: true }),
};

/** A sorted copy. Issues that tie are newest first, so the order never depends on how they arrived. */
export function sortIssues(issues: Issue[], sort: IssueSort): Issue[] {
  const compare = COMPARE[sort];
  return [...issues].sort((a, b) => compare(a, b) || newestFirst(a, b));
}
