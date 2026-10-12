import Link from "next/link";
import { DueDate } from "@/components/dates";
import { PriorityIcon, StatusIcon, priorityLabel, statusLabel } from "../meta";
import { PersonAvatar } from "../options";
import type { Issue } from "../types";
import { LabelPill } from "./label-chip";
import { SubIssueIndent, SubIssueProgress } from "./sub-issue-rows";

/**
 * One issue as a read-only line of a list: priority, key, title (which opens it), then its
 * project, labels, due date and assignee. `withStatus` adds the status icon, for lists that are
 * not already grouped by status.
 */
export function IssueRow({ issue, depth = 0, withStatus }: { issue: Issue; depth?: number; withStatus?: boolean }) {
  return (
    <li className="flex h-10 items-center gap-2 border-b pr-5 pl-5 hover:bg-muted max-md:h-12">
      <SubIssueIndent depth={depth} />
      <span title={priorityLabel(issue.priority)} className="flex shrink-0">
        <PriorityIcon priority={issue.priority} />
        <span className="sr-only">{priorityLabel(issue.priority)}</span>
      </span>
      <span className="w-20 shrink-0 truncate text-[0.8125rem] leading-5 text-muted-foreground tabular-nums">{issue.key}</span>
      {withStatus ? (
        <span title={statusLabel(issue.status)} className="flex shrink-0">
          <StatusIcon status={issue.status} />
          <span className="sr-only">{statusLabel(issue.status)}</span>
        </span>
      ) : null}
      <Link
        href={`/issue/${issue.key}`}
        className="min-w-0 flex-1 truncate rounded-sm font-medium outline-none focus-visible:ring-3 focus-visible:ring-ring/50"
      >
        {issue.title}
      </Link>
      <SubIssueProgress issue={issue} />
      {issue.project ? (
        <span className="hidden max-w-40 shrink-0 truncate text-[0.8125rem] leading-5 text-muted-foreground lg:block">
          {issue.project.name}
        </span>
      ) : null}
      {issue.labels.length > 0 ? (
        <span className="hidden shrink-0 items-center gap-1 sm:flex">
          {issue.labels.slice(0, 3).map((label) => (
            <LabelPill key={label.id} label={label} />
          ))}
        </span>
      ) : null}
      {issue.dueDate ? (
        <span className="hidden shrink-0 text-[0.8125rem] leading-5 text-muted-foreground tabular-nums sm:block">
          <DueDate value={issue.dueDate} />
        </span>
      ) : null}
      {issue.assignee ? (
        <span title={issue.assignee.displayName ?? issue.assignee.loginId} className="flex shrink-0">
          <PersonAvatar person={issue.assignee} />
          <span className="sr-only">{issue.assignee.displayName ?? issue.assignee.loginId}</span>
        </span>
      ) : null}
    </li>
  );
}
