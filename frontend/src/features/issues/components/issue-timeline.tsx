"use client";

import Link from "next/link";
import { useInBrowser, useToday } from "@/components/dates";
import { MonthRangeFields } from "@/features/share/components/month-range-fields";
import { ShareButton } from "@/features/share/components/share-button";
import { defaultRange, monthOfDate, type MonthRange } from "@/features/share/month-range";
import type { ShareTarget } from "@/features/share/types";
import { cn } from "@/lib/utils";
import { messages } from "@/messages";
import { statusLabel } from "../meta";
import { arrangeBySubIssues } from "../sub-issue-tree";
import { dayOf, localDay, spanOf } from "../timeline-span";
import type { Issue } from "../types";
import { SubIssueIndent } from "./sub-issue-rows";

const m = messages.issues.views;

const DAY = 86_400_000;
/** Width of one day on the chart. */
const DAY_WIDTH = 18;
/** Width of the column with the names, left of the chart. */
const NAME_WIDTH = 280;
/** One colour per project, in turn; distinct on both themes. */
const COLORS = ["#30a46c", "#f2994a", "#3e8ee0", "#8e6ee0", "#d6409f", "#c9a400", "#0b7285", "#e5484d"];

/**
 * What the chart needs of an issue. The shared timeline (docs/features/sharing.md) has no more than this, and
 * its `project` is a name only (its `id` is that name, which groups the issues of one project).
 */
export type TimelineIssue = Pick<Issue, "key" | "number" | "title" | "status" | "startDate" | "dueDate" | "createdAt" | "updatedAt" | "project" | "ancestors">;

const dateOfDay = (day: number) => new Date(day * DAY);
/** Today as a day number. Reads the clock, so it is only called in the browser. */
function todayDay(): number {
  return localDay(Date.now());
}
/** The first and the last day of a stretch of months (`2026-10`, the last one included). */
function daysOfRange({ from, to }: MonthRange): { first: number; last: number } {
  return {
    first: dayOf(`${from}-01`),
    // Day 0 of the month after is the last day of this one.
    last: Math.floor(Date.UTC(Number(to.slice(0, 4)), Number(to.slice(5, 7)), 0) / DAY),
  };
}

type Bar<T> = { issue: T; start: number; end: number; open: boolean };
type Group<T> = { key: string; title: string; color: string; bars: Bar<T>[] };

/** The span an issue covers (`spanOf`: an issue with only a due date is that one day, D-207). */
function barOf<T extends TimelineIssue>(issue: T, today: number): Bar<T> {
  return { issue, ...spanOf(issue, today) };
}

const defaultIssueHref = (issue: TimelineIssue) => `/issue/${issue.key}`;
const monthLabel = (month: string) => m.monthTitle(Number(month.slice(0, 4)), Number(month.slice(5, 7)));

/**
 * A timeline (Gantt) chart of issues grouped by project: one line per issue with a bar from its
 * start date to its due date (one day when it has no start date), a summary bar for each project, and a line for today. Issues in
 * no project form the last group. Inside a project sub-issues sit under their parent, one step in
 * for every level, as they do in the list; a parent that is in another project (or not on this
 * chart) gets a dimmed line of its own without a bar.
 *
 * The same chart is the shared page (docs/features/sharing.md): `range` fixes the months it shows (it then draws the
 * issues that fall in them, cut at its ends, without the week of air on either side), `issueHref` returning null draws
 * an issue that opens nothing (the page is read without signing in), and `share` adds the button that shares the months on
 * show, with a choice of which.
 */
export function IssueTimeline<T extends TimelineIssue = TimelineIssue>({
  issues: all,
  range,
  issueHref = defaultIssueHref,
  share,
}: {
  issues: T[];
  range?: MonthRange;
  /** Where an issue opens; null for an issue that opens nothing. */
  issueHref?: (issue: T) => string | null;
  /** What to share for the months picked (the share window starts with this month and the two after it). */
  share?: (range: MonthRange) => ShareTarget;
}) {
  // Where today falls depends on the clock, which only the browser may read while rendering.
  const inBrowser = useInBrowser();
  // Drawn again when the day turns, so the line for today moves on without a reload.
  useToday();
  if (!inBrowser) return <div className="h-96" aria-hidden="true" />;

  const shareBar = share ? (
    <div className="flex items-center border-t px-4 py-1.5">
      <ShareButton
        target={share(defaultRange(monthOfDate(new Date())))}
        pick={{
          render: (params, change) => (
            <MonthRangeFields value={{ from: params.from, to: params.to }} onChange={(next) => change({ ...params, ...next })} />
          ),
          summary: (params) => messages.share.range.summary(monthLabel(params.from), monthLabel(params.to)),
        }}
      />
    </div>
  ) : null;
  const fixed = range ? daysOfRange(range) : null;
  const today = todayDay();
  // In a fixed range only the issues that fall in it are drawn.
  const bars = all.map((issue) => barOf(issue, today)).filter((bar) => !fixed || (bar.end >= fixed.first && bar.start <= fixed.last));
  if (bars.length === 0) return shareBar;

  const byKey = new Map<string, Group<T>>();
  for (const bar of bars) {
    const key = bar.issue.project?.id ?? "";
    const group = byKey.get(key) ?? {
      key,
      title: bar.issue.project?.name ?? messages.issues.noProject,
      color: "",
      bars: [],
    };
    group.bars.push(bar);
    byKey.set(key, group);
  }
  const groups = [...byKey.values()]
    .sort((a, b) => {
      if (!a.key !== !b.key) return a.key ? -1 : 1;
      return a.title.localeCompare(b.title, "ko");
    })
    .map((group, index) => ({
      ...group,
      color: group.key ? COLORS[index % COLORS.length] : "var(--muted-foreground)",
      bars: group.bars.sort((a, b) => a.start - b.start || a.end - b.end),
      barOf: new Map(group.bars.map((bar) => [bar.issue.key, bar])),
    }));

  const edges = groups.flatMap((group) => group.bars.flatMap((bar) => [bar.start, bar.end]));
  // A week of air on both sides, and never so narrow that today falls off the chart; a fixed range is just its months.
  const first = fixed ? fixed.first : Math.min(...edges, today) - 7;
  const last = fixed ? fixed.last : Math.max(...edges, today) + 14;
  const days = last - first + 1;
  const width = days * DAY_WIDTH;
  const left = (day: number) => (day - first) * DAY_WIDTH;
  // The days of a bar that are on the chart: a bar in a fixed range is cut at its ends.
  const span = (start: number, end: number) => {
    const from = Math.max(start, first);
    const to = Math.min(end, last);
    return { left: left(from), width: Math.max(DAY_WIDTH, (to - from + 1) * DAY_WIDTH) };
  };
  const todayShown = today >= first && today <= last;

  // Month names along the top, and a line at the start of every week.
  const months: { day: number; label: string }[] = [];
  const weeks: number[] = [];
  for (let day = first; day <= last; day++) {
    const date = dateOfDay(day);
    if (date.getUTCDate() === 1 || day === first) months.push({ day, label: m.monthTitle(date.getUTCFullYear(), date.getUTCMonth() + 1) });
    if (date.getUTCDay() === 1) weeks.push(day);
  }

  return (
    <>
      {shareBar}
      <div className="overflow-x-auto border-t">
        <div className="relative" style={{ width: NAME_WIDTH + width }}>
          {/* Background of the chart area: week lines and today. */}
          <div aria-hidden="true" className="pointer-events-none absolute inset-y-0" style={{ left: NAME_WIDTH, width }}>
            {weeks.map((day) => (
              <span key={day} className="absolute inset-y-0 w-px bg-border" style={{ left: left(day) }} />
            ))}
            {todayShown ? <span className="absolute inset-y-0 z-10 w-px bg-primary" style={{ left: left(today) + DAY_WIDTH / 2 }} /> : null}
          </div>
          <div className="flex h-8 items-end border-b text-xs leading-4 text-muted-foreground">
            <div className="sticky left-0 z-20 h-full shrink-0 bg-background" style={{ width: NAME_WIDTH }} />
            <div className="relative h-full" style={{ width }}>
              {months.map((month) => (
                <span key={month.day} className="absolute bottom-1.5 pl-1.5 whitespace-nowrap tabular-nums" style={{ left: left(month.day) }}>
                  {month.label}
                </span>
              ))}
              {todayShown ? (
                <span
                  className="absolute bottom-1.5 z-10 -translate-x-1/2 rounded bg-primary px-1 font-medium whitespace-nowrap text-primary-foreground"
                  style={{ left: left(today) + DAY_WIDTH / 2 }}
                >
                  {m.today}
                </span>
              ) : null}
            </div>
          </div>
          {groups.map((group) => {
            const counted = group.bars.filter((bar) => bar.issue.status !== "CANCELED");
            const done = counted.filter((bar) => bar.issue.status === "DONE").length;
            const percent = counted.length === 0 ? 0 : Math.round((done / counted.length) * 100);
            const from = Math.min(...group.bars.map((bar) => bar.start));
            const to = Math.max(...group.bars.map((bar) => bar.end));
            return (
              <section key={group.key || "none"} aria-label={group.title} className="border-b">
                <div className="flex h-9 items-center">
                  <h2
                    className="sticky left-0 z-20 flex h-full shrink-0 items-center gap-2 bg-background px-5 text-[0.8125rem] leading-5 font-semibold"
                    style={{ width: NAME_WIDTH }}
                  >
                    <span aria-hidden="true" className="size-2.5 shrink-0 rounded-full" style={{ backgroundColor: group.color }} />
                    <span className="truncate" title={group.title}>
                      {group.title}
                    </span>
                    <span className="shrink-0 font-normal text-muted-foreground tabular-nums">{m.progress(done, counted.length, percent)}</span>
                  </h2>
                  <div className="relative h-full" style={{ width }}>
                    {/* The whole span of the project's issues, filled as far as they are done. */}
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
                        <li key={`parent-${row.parent.number}`} className="flex h-8 items-center bg-muted/60">
                          <Link
                            href={`/issue/${row.parent.key}`}
                            title={messages.issues.subIssues.parentContext}
                            // Opaque: the name column stays put over the bars when the chart scrolls sideways.
                            className="sticky left-0 z-20 flex h-full shrink-0 items-center gap-2 bg-muted pr-3 text-[0.8125rem] leading-5 text-muted-foreground outline-none hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50"
                            style={{ width: NAME_WIDTH, paddingLeft: 40 + row.depth * 20 }}
                          >
                            <span className="shrink-0 tabular-nums">{row.parent.key}</span>
                            <span className="truncate">{row.parent.title}</span>
                          </Link>
                          <div className="h-full" style={{ width }} />
                        </li>
                      );
                    }
                    const bar = group.barOf.get(row.issue.key);
                    if (!bar) return null;
                    const { issue, start, end, open } = bar;
                    const closed = issue.status === "DONE" || issue.status === "CANCELED";
                    const href = issueHref(issue);
                    const title = `${issue.key} ${issue.title} · ${statusLabel(issue.status)}${issue.dueDate ? ` · ${issue.dueDate}` : ` · ${m.noDueDate}`}`;
                    const nameClass = "sticky left-0 z-20 flex h-full shrink-0 items-center gap-2 bg-background pr-3 pl-10 text-[0.8125rem] leading-5 outline-none focus-visible:ring-3 focus-visible:ring-ring/50";
                    const nameBody = (
                      <>
                        <SubIssueIndent depth={row.depth} />
                        <span className="shrink-0 text-muted-foreground tabular-nums">{issue.key}</span>
                        <span className={cn("truncate", closed && "text-muted-foreground line-through")}>{issue.title}</span>
                      </>
                    );
                    const barClass = "absolute top-1/2 h-3 -translate-y-1/2 rounded outline-none focus-visible:ring-3 focus-visible:ring-ring/50";
                    const barStyle = {
                      ...span(start, end),
                      backgroundColor: open || closed ? `color-mix(in oklab, ${group.color} 40%, transparent)` : group.color,
                    };
                    return (
                      <li key={issue.key} className={cn("flex h-8 items-center", href && "hover:bg-muted/60")}>
                        {href ? (
                          <Link href={href} className={nameClass} style={{ width: NAME_WIDTH }}>
                            {nameBody}
                          </Link>
                        ) : (
                          <div className={nameClass} style={{ width: NAME_WIDTH }}>
                            {nameBody}
                          </div>
                        )}
                        <div className="relative h-full" style={{ width }}>
                          {href ? (
                            <Link href={href} title={title} className={barClass} style={barStyle}>
                              <span className="sr-only">{issue.title}</span>
                            </Link>
                          ) : (
                            <div title={title} className={barClass} style={barStyle}>
                              <span className="sr-only">{issue.title}</span>
                            </div>
                          )}
                          <span
                            aria-hidden="true"
                            // Fits in the two weeks of air right of the last bar, so a long title never leaves the chart.
                            className={cn("absolute top-1/2 max-w-60 -translate-y-1/2 truncate pl-2 text-xs leading-4", closed ? "text-muted-foreground" : null)}
                            style={{ left: left(Math.min(end, last)) + DAY_WIDTH }}
                          >
                            {issue.title}
                          </span>
                        </div>
                      </li>
                    );
                  })}
                </ul>
              </section>
            );
          })}
        </div>
      </div>
    </>
  );
}
