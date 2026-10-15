"use client";

import { memo } from "react";
import { DiamondIcon } from "lucide-react";
import type { ProjectMember } from "@/features/projects/types";
import { cn } from "@/lib/utils";
import { messages } from "@/messages";
import { StatusIcon } from "../meta";
import { priorityOptions, statusOptions } from "../options";
import type { Issue, IssueChanges } from "../types";
import { AssigneeChip } from "./assignee-chip";
import { ChipSelect } from "./chip-select";
import { DateRangeChip } from "./date-chips";
import { IssueAssignee, IssueDue, IssueLabels, IssueProjectName, IssueTitleLink, PriorityMark } from "./issue-row";
import { SubIssueIndent, SubIssueProgress } from "./sub-issue-rows";

const m = messages.issues;

/**
 * The columns at the end of a line, each with a width of its own so that they sit under one
 * another and under the header. The narrower the room, the fewer there are. A list that fills the
 * page goes by the window's width; one inside a panel (the sub-issues of an issue) by the width of
 * the panel, which then has to be a `@container`.
 */
const LAYOUTS = {
  page: {
    project: "hidden w-28 shrink-0 overflow-hidden md:block xl:w-32",
    labels: "hidden w-44 shrink-0 overflow-hidden xl:flex",
    milestone: "hidden w-32 shrink-0 overflow-hidden lg:flex",
    due: "hidden w-16 shrink-0 sm:block",
    assignee: "w-8 shrink-0 lg:w-28",
    assigneeName: "max-lg:hidden",
  },
  panel: {
    project: "hidden",
    labels: "hidden w-44 shrink-0 overflow-hidden @3xl:flex",
    milestone: "hidden w-32 shrink-0 overflow-hidden @xl:flex",
    due: "hidden w-16 shrink-0 @md:block",
    assignee: "w-8 shrink-0 @xl:w-28",
    assigneeName: "@max-xl:hidden",
  },
} as const;

export type IssueListLayout = keyof typeof LAYOUTS;

/**
 * The names of the columns, laid out exactly as a line is. `sticky` is the class that keeps it
 * under the page header while the list scrolls beneath it; a list in a panel does not stick.
 * `editable` lines have a button for the priority, which is wider than its icon.
 */
export function IssueListHeader({
  showProject,
  layout = "page",
  sticky,
  editable = false,
  className = "pr-5 pl-3",
}: {
  showProject: boolean;
  layout?: IssueListLayout;
  sticky?: string;
  editable?: boolean;
  /** The padding at both ends, the same as the lines'. */
  className?: string;
}) {
  const columns = LAYOUTS[layout];
  return (
    <div
      aria-hidden="true"
      className={cn(
        "flex h-8 items-center gap-2 text-xs leading-4 text-muted-foreground",
        // Only a header that stays put needs to hide the lines scrolling under it.
        sticky && cn("sticky z-20 bg-background", sticky),
        className,
      )}
    >
      <span className="w-7 shrink-0" />
      <span className={editable ? "w-7 shrink-0" : "w-4 shrink-0"} />
      <span className="w-16 shrink-0">{m.columns.key}</span>
      <span className="min-w-0 flex-1">{m.columns.title}</span>
      {showProject ? <span className={columns.project}>{m.project}</span> : null}
      <span className={columns.labels}>{m.labels}</span>
      <span className={columns.milestone}>{m.milestone}</span>
      <span className={columns.due}>{m.dueDate}</span>
      <span className={cn(columns.assignee, "overflow-hidden whitespace-nowrap")}>
        <span className={columns.assigneeName}>{m.assignee}</span>
      </span>
    </div>
  );
}

/** What a line needs to let its properties be changed in place: who can be given the issue, and where a change goes. */
export type IssueLineEditing = {
  /** Who the issue can be given to; null when its assignees cannot be changed here. */
  members: (issue: Issue) => ProjectMember[] | null;
  onChange: (number: number, changes: IssueChanges) => void;
};

/**
 * One line of a list. Kept from rendering again unless its own issue changed, so typing in the
 * search box or opening a menu does not redo every line of a long list.
 *
 * With `editable` the status opens a menu. With `editing` as well, so do the priority, the due
 * date and the assignees.
 */
export const IssueListRow = memo(function IssueListRow({
  issue,
  depth,
  editable,
  editing,
  showProject,
  layout = "page",
  onStatusChange,
  className = "pr-5 pl-3",
  presence = "issue-row",
}: {
  issue: Issue;
  depth: number;
  /** Whether the status can be changed right in the list. */
  editable: boolean;
  editing?: IssueLineEditing;
  /** On lists that span projects, each line says which project it is in. */
  showProject: boolean;
  layout?: IssueListLayout;
  onStatusChange: (number: number, status: Issue["status"]) => void;
  /** The padding at both ends. */
  className?: string;
  /** What others see this line as when it is pointed at; lines of another list over this one need their own. */
  presence?: string;
}) {
  const columns = LAYOUTS[layout];
  const edit = editable ? editing : undefined;
  const members = edit?.members(issue) ?? null;

  return (
    <li
      data-presence-id={`${presence}:${issue.number}`}
      data-tour={presence === "issue-row" ? "issue-row" : undefined}
      className={cn("flex h-10 items-center gap-2 border-b hover:bg-muted max-md:h-12", className)}
    >
      <SubIssueIndent depth={depth} />
      {editable ? (
        <ChipSelect
          iconOnly
          label={m.statusOf(issue.key)}
          value={issue.status}
          options={statusOptions}
          onChange={(status) => onStatusChange(issue.number, status)}
        />
      ) : (
        <span className="flex size-7 items-center justify-center">
          <StatusIcon status={issue.status} />
        </span>
      )}
      {edit ? (
        <ChipSelect
          iconOnly
          label={m.priorityOf(issue.key)}
          value={issue.priority}
          options={priorityOptions}
          onChange={(priority) => edit.onChange(issue.number, { priority })}
        />
      ) : (
        <PriorityMark priority={issue.priority} />
      )}
      <span className="w-16 shrink-0 truncate text-[0.8125rem] leading-5 text-muted-foreground tabular-nums">
        {issue.key}
      </span>
      <IssueTitleLink issue={issue} />
      <SubIssueProgress issue={issue} />
      {/* Every line has the same columns whether or not it has the value, so they line up down the list. */}
      {showProject ? (
        <span className={columns.project}>
          <IssueProjectName issue={issue} showNone />
        </span>
      ) : null}
      <span className={columns.labels}>
        <IssueLabels issue={issue} max={2} />
      </span>
      <span className={cn(columns.milestone, "items-center gap-1 text-[0.8125rem] leading-5 text-muted-foreground")}>
        {issue.milestone ? (
          <>
            <DiamondIcon className="size-3.5 shrink-0" aria-hidden="true" />
            <span className="truncate">{issue.milestone.name}</span>
          </>
        ) : null}
      </span>
      <span className={columns.due}>
        {edit ? (
          <DateRangeChip
            cell
            singleIsEnd
            label={m.dueDateOf(issue.key)}
            placeholder={m.noDueDate}
            start={issue.startDate}
            end={issue.dueDate}
            onChange={({ start, end }) => edit.onChange(issue.number, { startDate: start, dueDate: end })}
          />
        ) : (
          <IssueDue issue={issue} />
        )}
      </span>
      {members ? (
        <span className={columns.assignee}>
          <AssigneeChip
            cell
            label={m.assigneeOf(issue.key)}
            nameClass={columns.assigneeName}
            members={members}
            current={issue.assignees}
            value={issue.assignees.map((person) => person.id)}
            onChange={(assigneeIds) => edit?.onChange(issue.number, { assigneeIds })}
          />
        </span>
      ) : (
        <IssueAssignee issue={issue} className={columns.assignee} nameClass={columns.assigneeName} />
      )}
    </li>
  );
});
