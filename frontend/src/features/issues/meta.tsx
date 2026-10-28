import {
  CircleAlertIcon,
  CircleCheckIcon,
  CircleDashedIcon,
  CircleDotIcon,
  CircleIcon,
  CircleXIcon,
  ClipboardListIcon,
  EyeIcon,
  MinusIcon,
  SignalHighIcon,
  SignalLowIcon,
  SignalMediumIcon,
} from "lucide-react";
import { cn } from "@/lib/utils";
import { messages } from "@/messages";
import type { IssuePriority, IssueStatus } from "./types";

const m = messages.issues;

/** Order of the groups in a list: what is being worked on first, what is finished last. */
export const STATUS_ORDER: IssueStatus[] = ["IN_PROGRESS", "READY", "BACKLOG", "DONE", "CANCELED"];
/** Order in menus: the way an issue moves through the workflow. */
export const STATUSES: IssueStatus[] = ["BACKLOG", "READY", "IN_PROGRESS", "DONE", "CANCELED"];
export const PRIORITIES: IssuePriority[] = ["NONE", "URGENT", "HIGH", "MEDIUM", "LOW"];

export const statusLabel = (status: IssueStatus) => m.statuses[status];
export const priorityLabel = (priority: IssuePriority) => m.priorities[priority];

// Each state has its own shape as well as its own colour, so colour is never the only cue.

export function StatusIcon({ status, statusId, statusName, className }: { status: IssueStatus; statusId?: string; statusName?: string; className?: string }) {
  const props = { className: cn("size-4 shrink-0", className), "aria-hidden": true } as const;
  if (status === "IN_PROGRESS") {
    const name = statusName?.replace(/\s/g, "");
    if (statusId === "00000000-0000-7000-8000-0000000000a6" || name === "리뷰준비됨") {
      return <ClipboardListIcon {...props} className={cn(props.className, "text-warning")} />;
    }
    if (statusId === "00000000-0000-7000-8000-0000000000a7" || name === "리뷰중") {
      return <EyeIcon {...props} className={cn(props.className, "text-warning")} />;
    }
  }
  switch (status) {
    case "BACKLOG":
      return <CircleDashedIcon {...props} className={cn(props.className, "text-muted-foreground")} />;
    case "READY":
      return <CircleIcon {...props} className={cn(props.className, "text-muted-foreground")} />;
    case "IN_PROGRESS":
      return <CircleDotIcon {...props} className={cn(props.className, "text-warning")} />;
    case "DONE":
      return <CircleCheckIcon {...props} className={cn(props.className, "text-primary")} />;
    case "CANCELED":
      return <CircleXIcon {...props} className={cn(props.className, "text-muted-foreground")} />;
  }
}

export function PriorityIcon({ priority, className }: { priority: IssuePriority; className?: string }) {
  const props = { className: cn("size-4 shrink-0", className), "aria-hidden": true } as const;
  switch (priority) {
    case "NONE":
      return <MinusIcon {...props} className={cn(props.className, "text-muted-foreground")} />;
    case "URGENT":
      return <CircleAlertIcon {...props} className={cn(props.className, "text-urgent")} />;
    case "HIGH":
      return <SignalHighIcon {...props} />;
    case "MEDIUM":
      return <SignalMediumIcon {...props} />;
    case "LOW":
      return <SignalLowIcon {...props} />;
  }
}

/**
 * Due dates within the year are shown without it: 10-24.
 * Reads the clock, so render it through `<DueDate>` (components/dates.tsx), never directly.
 */
export function formatDueDate(dueDate: string, today = new Date()): string {
  return dueDate.startsWith(`${today.getFullYear()}-`) ? dueDate.slice(5) : dueDate;
}

/** The number in an issue key or address: "FB-12" and "12" are both 12. Null when there is none. */
export function issueNumberOf(key: string): number | null {
  const match = /(?:^|-)(\d{1,9})$/.exec(key);
  return match ? Number(match[1]) : null;
}
