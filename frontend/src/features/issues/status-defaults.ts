import type { IssueStatus } from "./types";

/**
 * A status an issue can be in (docs/features/statuses-and-estimates.md): it has a name and belongs to one of the five fixed
 * categories, which decides what it means (open or finished, what Git moves an issue to).
 */
export type IssueStatusItem = {
  id: string;
  category: IssueStatus;
  name: string;
  position: number;
};

/** The five every installation starts with (the ids are fixed by the migration): for example data and for before the list has come. */
export const BUILT_IN_STATUSES: IssueStatusItem[] = [
  { id: "00000000-0000-7000-8000-0000000000a1", category: "BACKLOG", name: "백로그", position: 10 },
  { id: "00000000-0000-7000-8000-0000000000a2", category: "READY", name: "준비됨", position: 10 },
  { id: "00000000-0000-7000-8000-0000000000a3", category: "IN_PROGRESS", name: "진행 중", position: 10 },
  { id: "00000000-0000-7000-8000-0000000000a4", category: "DONE", name: "완료", position: 10 },
  { id: "00000000-0000-7000-8000-0000000000a5", category: "CANCELED", name: "취소", position: 10 },
];

/** The order of the groups of a list: what is being worked on first, what is finished last. */
const LIST_CATEGORY_ORDER: IssueStatus[] = ["IN_PROGRESS", "READY", "BACKLOG", "DONE", "CANCELED"];

/** The statuses in the order of a list or a board: the category order of the list, then the position. */
export function listOrder(statuses: IssueStatusItem[]): IssueStatusItem[] {
  return [...statuses].sort(
    (a, b) => LIST_CATEGORY_ORDER.indexOf(a.category) - LIST_CATEGORY_ORDER.indexOf(b.category) || a.position - b.position,
  );
}

/** Whether an issue is in what a filter or a link names: a status id, or a whole category (`?status=IN_PROGRESS`). */
export function inStatus(issue: { status: IssueStatus; statusId: string }, wanted: string): boolean {
  return issue.statusId === wanted || issue.status === wanted;
}

/** The first status of a category: where an issue goes when only the category is known. */
export function defaultStatusOf(statuses: IssueStatusItem[], category: IssueStatus): IssueStatusItem {
  return statuses.find((status) => status.category === category) ?? BUILT_IN_STATUSES.find((status) => status.category === category)!;
}
