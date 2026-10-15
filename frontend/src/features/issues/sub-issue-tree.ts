import type { Issue } from "./types";

/** An issue above another, as an issue carries it (`parent`, `ancestors`). */
type IssueRef = NonNullable<Issue["parent"]>;

/** One line of a list that shows sub-issues under their parents. */
export type IssueRow =
  | { kind: "issue"; issue: Issue; depth: number }
  /** An issue above the rows that follow but not in the list itself, shown only so they have their place. */
  | { kind: "context"; parent: IssueRef; depth: number };

type Node = {
  ref: IssueRef;
  issue?: Issue;
  /** Where the issue itself stands in the group, or where the first issue under it does. */
  order: number;
  children: Node[];
};

/**
 * Puts the issues of one group (a status) in the order they are drawn: each issue followed by its
 * sub-issues one level in. A sub-issue whose parent is not in the group (it has another status,
 * or is not in this list at all) is still shown where it belongs: every issue above it, from the
 * top-level one down, appears as a dimmed line of its own (the user's call, 2026-10-15: the parent
 * alone did not say where a deep sub-issue came from).
 */
export function arrangeBySubIssues(group: Issue[]): IssueRow[] {
  const nodes = new Map<number, Node>();
  const roots: Node[] = [];
  group.forEach((issue, index) => {
    // The path from the top-level issue down to this one.
    const path: IssueRef[] = [...issue.ancestors, { number: issue.number, key: issue.key, title: issue.title }];
    let parent: Node | undefined;
    path.forEach((ref, step) => {
      let node = nodes.get(ref.number);
      if (!node) {
        node = { ref, order: index, children: [] };
        nodes.set(ref.number, node);
        (parent ? parent.children : roots).push(node);
      }
      if (step === path.length - 1) {
        node.issue = issue;
        node.order = index;
      }
      parent = node;
    });
  });

  const rows: IssueRow[] = [];
  const walk = (node: Node, depth: number) => {
    rows.push(node.issue ? { kind: "issue", issue: node.issue, depth } : { kind: "context", parent: node.ref, depth });
    // Sub-issues read in the order they were made, the same as on their parent's own page.
    for (const child of [...node.children].sort((a, b) => a.ref.number - b.ref.number)) walk(child, depth + 1);
  };
  // The list itself keeps its own order: a tree stands where its top issue stands, or where the
  // first issue in it does when the top one is not in the group.
  for (const root of [...roots].sort((a, b) => a.order - b.order)) walk(root, 0);
  return rows;
}
