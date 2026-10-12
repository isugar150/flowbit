import Link from "next/link";
import { CircleDashedIcon } from "lucide-react";
import { messages } from "@/messages";
import type { Issue } from "../types";

const m = messages.issues.subIssues;

/** The bend that ties a sub-issue to the line above it, one step in for every level. */
export function SubIssueIndent({ depth }: { depth: number }) {
  if (depth === 0) return null;
  return (
    <span aria-hidden="true" className="flex h-full shrink-0 items-start" style={{ width: depth * 20 }}>
      <span className="ml-auto h-1/2 w-3 rounded-bl-md border-b border-l border-foreground/25" />
    </span>
  );
}

/** "1/3" next to an issue that has sub-issues: how many are done out of how many. */
export function SubIssueProgress({ issue }: { issue: Issue }) {
  if (issue.subIssueCount === 0) return null;
  return (
    <span
      title={m.progress(issue.subIssueDoneCount, issue.subIssueCount)}
      className="flex h-6 shrink-0 items-center gap-1 rounded-full border px-2 text-xs leading-4 text-muted-foreground tabular-nums"
    >
      <CircleDashedIcon className="size-3" aria-hidden="true" />
      {issue.subIssueDoneCount}/{issue.subIssueCount}
      <span className="sr-only">{m.progress(issue.subIssueDoneCount, issue.subIssueCount)}</span>
    </span>
  );
}

/**
 * A dimmed line for the parent of the sub-issues below it, in a group the parent itself is not
 * in (another status, or not on this list). It only gives them their place; open it to see it.
 */
export function SubIssueContextRow({ parent, className }: { parent: NonNullable<Issue["parent"]>; className?: string }) {
  return (
    <li className={className}>
      <span className="w-20 shrink-0 truncate text-[0.8125rem] leading-5 text-muted-foreground tabular-nums">{parent.key}</span>
      <Link
        href={`/issue/${parent.key}`}
        title={m.parentContext}
        className="min-w-0 flex-1 truncate rounded-sm text-muted-foreground outline-none hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50"
      >
        {parent.title}
      </Link>
    </li>
  );
}
