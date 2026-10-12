import type { Issue } from "./types";

/** One line of a list that shows sub-issues under their parents. */
export type IssueRow =
  | { kind: "issue"; issue: Issue; depth: number }
  /** The parent of the rows that follow, shown only so they have something to hang under. */
  | { kind: "context"; parent: NonNullable<Issue["parent"]>; depth: 0 };

/**
 * Puts the issues of one group (a status) in the order they are drawn: each issue followed by its
 * sub-issues one level in. A sub-issue whose parent is not in the group (it has another status,
 * or is not in this list at all) is still shown under its parent: the parent appears above it as
 * a dimmed line of its own.
 */
export function arrangeBySubIssues(group: Issue[]): IssueRow[] {
  const inGroup = new Set(group.map((issue) => issue.number));
  const children = new Map<number, Issue[]>();
  const tops: Issue[] = [];
  for (const issue of group) {
    if (issue.parent && inGroup.has(issue.parent.number)) {
      children.set(issue.parent.number, [...(children.get(issue.parent.number) ?? []), issue]);
    } else {
      tops.push(issue);
    }
  }
  const rows: IssueRow[] = [];
  // Sub-issues read in the order they were made, the same as on their parent's own page; the
  // list itself comes newest first.
  const inOrder = (issues: Issue[]) => [...issues].sort((a, b) => a.number - b.number);
  const add = (issue: Issue, depth: number) => {
    rows.push({ kind: "issue", issue, depth });
    for (const child of inOrder(children.get(issue.number) ?? [])) add(child, depth + 1);
  };
  const shownParents = new Set<number>();
  for (const issue of tops) {
    const parent = issue.parent;
    if (!parent) {
      add(issue, 0);
      continue;
    }
    if (shownParents.has(parent.number)) continue;
    shownParents.add(parent.number);
    rows.push({ kind: "context", parent, depth: 0 });
    // Brothers and sisters stay together under the one line for their parent.
    for (const sibling of inOrder(tops)) if (sibling.parent?.number === parent.number) add(sibling, 1);
  }
  return rows;
}
