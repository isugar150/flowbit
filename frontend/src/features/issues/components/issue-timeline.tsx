"use client";

import Link from "next/link";
import { useInBrowser } from "@/components/dates";
import { cn } from "@/lib/utils";
import { messages } from "@/messages";
import { statusLabel } from "../meta";
import { arrangeBySubIssues } from "../sub-issue-tree";
import type { Issue } from "../types";
import { SubIssueIndent } from "./sub-issue-rows";

const m = messages.issues.views;

const DAY = 86_400_000;
/** Width of one day on the chart. */
const DAY_WIDTH = 18;
/** Width of the column with the names, left of the chart. */
const NAME_WIDTH = 280;
/** One colour per milestone, in turn; distinct on both themes. */
const COLORS = ["#30a46c", "#f2994a", "#3e8ee0", "#8e6ee0", "#d6409f", "#c9a400", "#0b7285", "#e5484d"];

/** A calendar day as a number of days, so days can be subtracted. */
const dayOf = (date: string) => Math.floor(Date.UTC(Number(date.slice(0, 4)), Number(date.slice(5, 7)) - 1, Number(date.slice(8, 10))) / DAY);
/** The reader's own calendar day of a moment. */
function localDay(iso: string | number): number {
  const date = new Date(iso);
  return Math.floor(Date.UTC(date.getFullYear(), date.getMonth(), date.getDate()) / DAY);
}
const dateOfDay = (day: number) => new Date(day * DAY);
/** Today as a day number. Reads the clock, so it is only called in the browser. */
function todayDay(): number {
  return localDay(Date.now());
}

type Bar = { issue: Issue; start: number; end: number; open: boolean };
type Group = { key: string; title: string; project: string | null; target: number | null; color: string; bars: Bar[] };

/**
 * The span an issue covers: from its start date (or the day it was created) to its due date. Without a due date the
 * end is not known, so a finished issue ends when it was last changed and an unfinished one
 * reaches today, drawn lighter (`open`).
 */
function barOf(issue: Issue, today: number): Bar {
  // An issue with its own first day starts there; otherwise on the day it was made.
  const start = issue.startDate ? dayOf(issue.startDate) : localDay(issue.createdAt);
  const finished = issue.status === "DONE" || issue.status === "CANCELED";
  const end = issue.dueDate ? dayOf(issue.dueDate) : finished ? localDay(issue.updatedAt) : today;
  return { issue, start: Math.min(start, end), end: Math.max(start, end), open: !issue.dueDate && !finished };
}

/**
 * A timeline (Gantt) chart of issues grouped by milestone: one line per issue with a bar from the
 * day it was made to its due date, a summary bar and a diamond on the target date for each
 * milestone, and a line for today. Issues in no milestone form the last group. Inside a milestone
 * sub-issues sit under their parent, one step in for every level, as they do in the list; a parent
 * that is in another milestone (or not on this chart) gets a dimmed line of its own without a bar.
 */
export function IssueMilestoneChart({ issues }: { issues: Issue[] }) {
  // Where today falls depends on the clock, which only the browser may read while rendering.
  const inBrowser = useInBrowser();
  if (!inBrowser) return <div className="h-96" aria-hidden="true" />;
  if (issues.length === 0) return null;

  const today = todayDay();
  const byKey = new Map<string, Group>();
  for (const issue of issues) {
    const key = issue.milestone?.id ?? "";
    const group = byKey.get(key) ?? {
      key,
      title: issue.milestone?.name ?? m.noMilestone,
      project: issue.milestone ? (issue.project?.name ?? null) : null,
      target: issue.milestone?.targetDate ? dayOf(issue.milestone.targetDate) : null,
      color: "",
      bars: [],
    };
    group.bars.push(barOf(issue, today));
    byKey.set(key, group);
  }
  const groups = [...byKey.values()]
    .sort((a, b) => {
      if (!a.key !== !b.key) return a.key ? -1 : 1;
      return `${a.project ?? ""} ${a.title}`.localeCompare(`${b.project ?? ""} ${b.title}`, "ko");
    })
    .map((group, index) => ({
      ...group,
      color: group.key ? COLORS[index % COLORS.length] : "var(--muted-foreground)",
      bars: group.bars.sort((a, b) => a.start - b.start || a.end - b.end),
      barOf: new Map(group.bars.map((bar) => [bar.issue.id, bar])),
    }));

  const edges = groups.flatMap((group) => [...group.bars.flatMap((bar) => [bar.start, bar.end]), ...(group.target === null ? [] : [group.target])]);
  // A week of air on both sides, and never so narrow that today falls off the chart.
  const first = Math.min(...edges, today) - 7;
  const last = Math.max(...edges, today) + 14;
  const days = last - first + 1;
  const width = days * DAY_WIDTH;
  const left = (day: number) => (day - first) * DAY_WIDTH;
  const span = (start: number, end: number) => ({ left: left(start), width: Math.max(DAY_WIDTH, (end - start + 1) * DAY_WIDTH) });

  // Month names along the top, and a line at the start of every week.
  const months: { day: number; label: string }[] = [];
  const weeks: number[] = [];
  for (let day = first; day <= last; day++) {
    const date = dateOfDay(day);
    if (date.getUTCDate() === 1 || day === first) months.push({ day, label: m.monthTitle(date.getUTCFullYear(), date.getUTCMonth() + 1) });
    if (date.getUTCDay() === 1) weeks.push(day);
  }

  return (
    <div className="overflow-x-auto border-t">
      <div className="relative" style={{ width: NAME_WIDTH + width }}>
        {/* Background of the chart area: week lines and today. */}
        <div aria-hidden="true" className="pointer-events-none absolute inset-y-0" style={{ left: NAME_WIDTH, width }}>
          {weeks.map((day) => (
            <span key={day} className="absolute inset-y-0 w-px bg-border" style={{ left: left(day) }} />
          ))}
          <span className="absolute inset-y-0 z-10 w-px bg-primary" style={{ left: left(today) + DAY_WIDTH / 2 }} />
        </div>
        <div className="flex h-8 items-end border-b text-xs leading-4 text-muted-foreground">
          <div className="sticky left-0 z-20 h-full shrink-0 bg-background" style={{ width: NAME_WIDTH }} />
          <div className="relative h-full" style={{ width }}>
            {months.map((month) => (
              <span key={month.day} className="absolute bottom-1.5 pl-1.5 whitespace-nowrap tabular-nums" style={{ left: left(month.day) }}>
                {month.label}
              </span>
            ))}
            <span
              className="absolute bottom-1.5 z-10 -translate-x-1/2 rounded bg-primary px-1 font-medium whitespace-nowrap text-primary-foreground"
              style={{ left: left(today) + DAY_WIDTH / 2 }}
            >
              {m.today}
            </span>
          </div>
        </div>
        {groups.map((group) => {
          const counted = group.bars.filter((bar) => bar.issue.status !== "CANCELED");
          const done = counted.filter((bar) => bar.issue.status === "DONE").length;
          const percent = counted.length === 0 ? 0 : Math.round((done / counted.length) * 100);
          const from = Math.min(...group.bars.map((bar) => bar.start));
          const to = Math.max(...group.bars.map((bar) => bar.end), group.target ?? -Infinity);
          return (
            <section key={group.key || "none"} aria-label={group.title} className="border-b">
              <div className="flex h-9 items-center">
                <h2
                  className="sticky left-0 z-20 flex h-full shrink-0 items-center gap-2 bg-background px-5 text-[0.8125rem] leading-5 font-semibold"
                  style={{ width: NAME_WIDTH }}
                >
                  <span aria-hidden="true" className="size-2.5 shrink-0 rounded-full" style={{ backgroundColor: group.color }} />
                  <span className="truncate" title={group.project ? `${group.project} · ${group.title}` : group.title}>
                    {group.title}
                  </span>
                  <span className="shrink-0 font-normal text-muted-foreground tabular-nums">{m.progress(done, counted.length, percent)}</span>
                </h2>
                <div className="relative h-full" style={{ width }}>
                  {/* The whole span of the milestone, filled as far as its issues are done. */}
                  <span
                    aria-hidden="true"
                    className="absolute top-1/2 h-1 -translate-y-1/2 overflow-hidden rounded-full"
                    style={{ ...span(from, to), backgroundColor: `color-mix(in oklab, ${group.color} 30%, transparent)` }}
                  >
                    <span className="block h-full" style={{ width: `${percent}%`, backgroundColor: group.color }} />
                  </span>
                </div>
              </div>
              <ul>
                {arrangeBySubIssues(group.bars.map((bar) => bar.issue)).map((row) => {
                  if (row.kind === "context") {
                    return (
                      <li key={`parent-${row.parent.number}`} className="flex h-8 items-center">
                        <Link
                          href={`/issue/${row.parent.key}`}
                          title={messages.issues.subIssues.parentContext}
                          className="sticky left-0 z-20 flex h-full shrink-0 items-center gap-2 bg-background pr-3 pl-10 text-[0.8125rem] leading-5 text-muted-foreground outline-none hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50"
                          style={{ width: NAME_WIDTH }}
                        >
                          <span className="shrink-0 tabular-nums">{row.parent.key}</span>
                          <span className="truncate">{row.parent.title}</span>
                        </Link>
                        <div className="h-full" style={{ width }} />
                      </li>
                    );
                  }
                  const { issue, start, end, open } = group.barOf.get(row.issue.id)!;
                  const closed = issue.status === "DONE" || issue.status === "CANCELED";
                  return (
                    <li key={issue.id} className="flex h-8 items-center hover:bg-muted/60">
                      <Link
                        href={`/issue/${issue.key}`}
                        className="sticky left-0 z-20 flex h-full shrink-0 items-center gap-2 bg-background pr-3 pl-10 text-[0.8125rem] leading-5 outline-none focus-visible:ring-3 focus-visible:ring-ring/50"
                        style={{ width: NAME_WIDTH }}
                      >
                        <SubIssueIndent depth={row.depth} />
                        <span className="shrink-0 text-muted-foreground tabular-nums">{issue.key}</span>
                        <span className={cn("truncate", closed && "text-muted-foreground line-through")}>{issue.title}</span>
                      </Link>
                      <div className="relative h-full" style={{ width }}>
                        <Link
                          href={`/issue/${issue.key}`}
                          title={`${issue.key} ${issue.title} · ${statusLabel(issue.status)}${issue.dueDate ? ` · ${issue.dueDate}` : ` · ${m.noDueDate}`}`}
                          className="absolute top-1/2 h-3 -translate-y-1/2 rounded outline-none focus-visible:ring-3 focus-visible:ring-ring/50"
                          style={{
                            ...span(start, end),
                            backgroundColor: open || closed ? `color-mix(in oklab, ${group.color} 40%, transparent)` : group.color,
                          }}
                        >
                          <span className="sr-only">{issue.title}</span>
                        </Link>
                        <span
                          aria-hidden="true"
                          // Fits in the two weeks of air right of the last bar, so a long title never leaves the chart.
                          className={cn("absolute top-1/2 max-w-60 -translate-y-1/2 truncate pl-2 text-xs leading-4", closed ? "text-muted-foreground" : null)}
                          style={{ left: left(end) + DAY_WIDTH }}
                        >
                          {issue.title}
                        </span>
                      </div>
                    </li>
                  );
                })}
                {group.target !== null ? (
                  <li className="flex h-8 items-center">
                    <span
                      className="sticky left-0 z-20 flex h-full shrink-0 items-center bg-background pr-3 pl-10 text-[0.8125rem] leading-5 text-muted-foreground"
                      style={{ width: NAME_WIDTH }}
                    >
                      {m.targetDate}
                    </span>
                    <div className="relative h-full" style={{ width }}>
                      {/* The diamond sits on the day the milestone is due. */}
                      <span
                        aria-hidden="true"
                        className="absolute top-1/2 size-3 -translate-x-1/2 -translate-y-1/2 rotate-45 rounded-[2px]"
                        style={{ left: left(group.target) + DAY_WIDTH / 2, backgroundColor: group.color }}
                      />
                      <span
                        className="absolute top-1/2 -translate-y-1/2 pl-3 text-xs leading-4 whitespace-nowrap text-muted-foreground tabular-nums"
                        style={{ left: left(group.target) + DAY_WIDTH }}
                      >
                        {dateOfDay(group.target).toISOString().slice(0, 10)}
                      </span>
                    </div>
                  </li>
                ) : null}
              </ul>
            </section>
          );
        })}
      </div>
    </div>
  );
}
