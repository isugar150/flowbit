"use client";

import { cloneElement, useState } from "react";
import Link from "next/link";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { cn } from "@/lib/utils";
import { messages } from "@/messages";
import { StatusIcon, statusLabel } from "../meta";
import type { CardActivity } from "../card-activity";
import { dayParts, type CalendarIssue } from "./calendar-dates";
import { IssueHoverCard, type IssueCardData } from "./issue-card";
import { IssueAssignee } from "./issue-row";
import { useBarHandle } from "./use-calendar-dnd";

const m = messages.issues.views;

/** One line of a day's list. It can be dragged to another day like a bar, which closes the list (`onStart`) so that the days under it can be reached. */
function DayIssueRow<T extends CalendarIssue>({
  issue,
  dayKey,
  movable,
  issueHref,
  cardOf,
  loadActivity,
  onStart,
  onOpen,
}: {
  issue: T;
  dayKey: string;
  movable: boolean;
  issueHref: (issue: T) => string | null;
  cardOf?: (issue: T) => IssueCardData;
  loadActivity?: (issue: T) => Promise<CardActivity[]>;
  onStart: () => void;
  onOpen: () => void;
}) {
  const { ref, listeners } = useBarHandle({
    id: `list:${issue.number}:${dayKey}`,
    number: issue.number,
    mode: "move",
    anchor: dayKey,
    disabled: !movable || !issue.dueDate,
    onStart,
  });
  const href = issueHref(issue);
  const className = cn("flex items-center gap-2 rounded-md px-2 py-1.5 outline-none", href && "hover:bg-accent focus-visible:bg-accent", movable && "cursor-grab");
  const body = (
    <>
      <StatusIcon status={issue.status} className="size-4 shrink-0" />
      <span className="min-w-0 flex-1">
        <span
          className={cn(
            "block truncate text-[0.8125rem] leading-5",
            (issue.status === "DONE" || issue.status === "CANCELED") && "text-muted-foreground line-through",
          )}
        >
          {issue.title}
        </span>
        <span className="block truncate text-xs leading-4 text-muted-foreground tabular-nums">
          {issue.key} · {statusLabel(issue.status)}
          {issue.startDate && issue.dueDate ? ` · ${issue.startDate.slice(5)} ~ ${issue.dueDate.slice(5)}` : null}
        </span>
      </span>
      {issue.assignees ? <IssueAssignee issue={{ assignees: issue.assignees }} className="shrink-0" /> : null}
    </>
  );
  const element = href ? (
    // Dragging a line is dnd-kit's (use-calendar-dnd.ts), not the browser's drag of a link.
    <Link href={href} draggable={false} onClick={onOpen} className={className} />
  ) : (
    <div className={className} />
  );
  const card = cardOf?.(issue);
  return (
    <li ref={ref} {...listeners}>
      {card ? (
        // To the side of the list, so that the card does not cover the lines under it.
        <IssueHoverCard render={element} card={card} load={loadActivity ? () => loadActivity(issue) : undefined} side="right" align="start">
          {body}
        </IssueHoverCard>
      ) : (
        cloneElement(element, undefined, body)
      )}
    </li>
  );
}

/**
 * The "+N개 더" under a day of the calendar: opens every issue that is on that day, each with its
 * status, title and assignees, and opening one goes to its page. It is a button of its own, so
 * pressing it does not start a new issue on the day as pressing the empty part of the day does. With `cardOf` a line has the
 * same card as the bar of the issue in the calendar (D-201), opening to the side of the list. A line can be dragged to another
 * day like a bar (D-222, dnd-kit D-235): the list closes as soon as the drag starts, so that the days under it can be reached.
 */
export function DayIssues<T extends CalendarIssue>({
  dayKey,
  issues,
  hidden,
  issueHref,
  cardOf,
  loadActivity,
  canDrag,
}: {
  dayKey: string;
  issues: T[];
  hidden: number;
  /** Where an issue opens; null for an issue that opens nothing (the shared calendar). */
  issueHref: (issue: T) => string | null;
  /** What the card over an issue says; without it a line has no card. */
  cardOf?: (issue: T) => IssueCardData;
  /** The last history entries of an issue, for the card, when `cardOf` does not carry them. */
  loadActivity?: (issue: T) => Promise<CardActivity[]>;
  /** Whether a line can be dragged to another day; without it none can. */
  canDrag?: (issue: T) => boolean;
}) {
  const [open, setOpen] = useState(false);
  const { month, day, weekday } = dayParts(dayKey);
  const title = m.dayTitle(month, day, m.weekdays[weekday]);
  return (
    // Modal, so that a press outside only closes it and does not also reach the day under it.
    <Popover open={open} onOpenChange={setOpen} modal>
      <PopoverTrigger
        aria-label={m.dayShowAll(title, issues.length)}
        className="block h-5 w-full cursor-pointer rounded-md px-1.5 text-left text-xs leading-4 text-muted-foreground outline-none hover:bg-accent hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50"
      >
        {m.more(hidden)}
      </PopoverTrigger>
      <PopoverContent align="start" className="w-80 p-1">
        <h3 className="flex items-baseline gap-2 px-2 pt-1.5 pb-1 text-[0.8125rem] leading-5 font-medium">
          {title}
          <span className="text-xs leading-4 font-normal text-muted-foreground tabular-nums">{m.dayCount(issues.length)}</span>
        </h3>
        <ul className="max-h-72 overflow-y-auto">
          {issues.map((issue) => (
            <DayIssueRow
              key={issue.key}
              issue={issue}
              dayKey={dayKey}
              movable={canDrag?.(issue) ?? false}
              issueHref={issueHref}
              cardOf={cardOf}
              loadActivity={loadActivity}
              onStart={() => setOpen(false)}
              onOpen={() => setOpen(false)}
            />
          ))}
        </ul>
      </PopoverContent>
    </Popover>
  );
}
