"use client";

import { useState } from "react";
import Link from "next/link";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { cn } from "@/lib/utils";
import { messages } from "@/messages";
import { StatusIcon, statusLabel } from "../meta";
import type { Issue } from "../types";
import { dayParts } from "./calendar-dates";
import { IssueAssignee } from "./issue-row";

const m = messages.issues.views;

/**
 * The "+N개 더" under a day of the calendar: opens every issue that is on that day, each with its
 * status, title and assignees, and opening one goes to its page. It is a button of its own, so
 * pressing it does not start a new issue on the day as pressing the empty part of the day does.
 */
export function DayIssues({ dayKey, issues, hidden }: { dayKey: string; issues: Issue[]; hidden: number }) {
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
            <li key={issue.id}>
              <Link
                href={`/issue/${issue.key}`}
                onClick={() => setOpen(false)}
                className="flex items-center gap-2 rounded-md px-2 py-1.5 outline-none hover:bg-accent focus-visible:bg-accent"
              >
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
                <IssueAssignee issue={issue} className="shrink-0" />
              </Link>
            </li>
          ))}
        </ul>
      </PopoverContent>
    </Popover>
  );
}
