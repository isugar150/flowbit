import Link from "next/link";
import { CircleUserRoundIcon } from "lucide-react";
import { DueDate } from "@/components/dates";
import { PeopleAvatars, PeopleMarks, personName } from "@/components/person";
import { messages } from "@/messages";
import { PriorityIcon, StatusIcon, priorityLabel, statusLabel } from "../meta";
import { assigneeSummary } from "../options";
import type { Issue, IssuePriority, IssueStatus } from "../types";
import { LabelPill } from "./label-chip";
import { SubIssueIndent, SubIssueProgress } from "./sub-issue-rows";

const metaText = "text-[0.8125rem] leading-5 text-muted-foreground";

/** The priority icon, with its name as a tooltip and for screen readers. */
export function PriorityMark({ priority }: { priority: IssuePriority }) {
  return (
    <span title={priorityLabel(priority)} className="flex shrink-0">
      <PriorityIcon priority={priority} />
      <span className="sr-only">{priorityLabel(priority)}</span>
    </span>
  );
}

/** The status icon, with its name as a tooltip and for screen readers. */
export function StatusMark({ status }: { status: IssueStatus }) {
  return (
    <span title={statusLabel(status)} className="flex shrink-0">
      <StatusIcon status={status} />
      <span className="sr-only">{statusLabel(status)}</span>
    </span>
  );
}

/** The title of an issue as the link that opens it; it takes the room the rest of the line leaves. */
export function IssueTitleLink({ issue }: { issue: Pick<Issue, "key" | "title"> }) {
  return (
    <Link
      href={`/issue/${issue.key}`}
      className="min-w-0 flex-1 truncate rounded-sm font-medium outline-none focus-visible:ring-3 focus-visible:ring-ring/50"
    >
      {issue.title}
    </Link>
  );
}

/**
 * The project an issue is linked to, on screens wide enough for it. In a column of its own
 * (`showNone`) the column decides when it shows, and an issue outside every project says so.
 */
export function IssueProjectName({ issue, showNone = false }: { issue: Issue; showNone?: boolean }) {
  if (showNone) {
    return issue.project ? (
      <span className={`block truncate ${metaText}`}>{issue.project.name}</span>
    ) : (
      <span className={`block truncate ${metaText} opacity-60`}>{messages.issues.noProject}</span>
    );
  }
  if (!issue.project) return null;
  return <span className={`hidden max-w-40 shrink-0 truncate lg:block ${metaText}`}>{issue.project.name}</span>;
}

/** The first few labels of an issue, on screens wide enough for them. */
export function IssueLabels({ issue, max = 3 }: { issue: Issue; max?: number }) {
  if (issue.labels.length === 0) return null;
  return (
    <span className="hidden shrink-0 items-center gap-1 sm:flex">
      {issue.labels.slice(0, max).map((label) => (
        <LabelPill key={label.id} label={label} />
      ))}
    </span>
  );
}

/** The due date of an issue, on screens wide enough for it. */
export function IssueDue({ issue }: { issue: Issue }) {
  if (!issue.dueDate) return null;
  return (
    <span className={`hidden shrink-0 tabular-nums sm:block ${metaText}`}>
      <DueDate value={issue.dueDate} />
    </span>
  );
}

/**
 * Who has the issue, as a column of its own at the end of a line: the avatars (two at most,
 * overlapping), with the name, or the first name and how many more, beside them on screens wide
 * enough. An issue no one has says so with an empty mark, so the column lines up.
 */
export function IssueAssignee({
  issue,
  className = "w-8 shrink-0 lg:w-28",
  nameClass = "max-lg:hidden",
}: {
  issue: Issue;
  /** The column's width. */
  className?: string;
  /** When the name beside the avatars shows. */
  nameClass?: string;
}) {
  const people = issue.assignees;
  const names = people.length > 0 ? people.map(personName).join(", ") : messages.issues.noAssignee;
  return (
    <span title={names} className={`flex items-center gap-1.5 ${className}`}>
      {people.length > 0 ? (
        <PeopleAvatars people={people} max={2} />
      ) : (
        <CircleUserRoundIcon className="size-4.5 text-muted-foreground/60" aria-hidden="true" />
      )}
      {people.length > 0 ? (
        <span aria-hidden="true" className={`block min-w-0 truncate ${nameClass} ${metaText}`}>
          {assigneeSummary(people)}
        </span>
      ) : null}
      <span className="sr-only">{names}</span>
    </span>
  );
}

/**
 * One issue as a read-only line of a list: priority, key, title (which opens it), then its
 * project, labels, due date and assignees. `withStatus` adds the status icon, for lists that are
 * not already grouped by status.
 */
export function IssueRow({ issue, depth = 0, withStatus }: { issue: Issue; depth?: number; withStatus?: boolean }) {
  return (
    <li className="flex h-10 items-center gap-2 border-b pr-5 pl-5 hover:bg-muted max-md:h-12">
      <SubIssueIndent depth={depth} />
      <PriorityMark priority={issue.priority} />
      <span className={`w-20 shrink-0 truncate tabular-nums ${metaText}`}>{issue.key}</span>
      {withStatus ? <StatusMark status={issue.status} /> : null}
      <IssueTitleLink issue={issue} />
      <SubIssueProgress issue={issue} />
      <IssueProjectName issue={issue} />
      <IssueLabels issue={issue} />
      <IssueDue issue={issue} />
      {issue.assignees.length > 0 ? <PeopleMarks people={issue.assignees} /> : null}
    </li>
  );
}
