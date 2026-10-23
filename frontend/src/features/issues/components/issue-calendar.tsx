"use client";

import { cloneElement, useEffect, useRef, useState } from "react";
import Link from "next/link";
import { ChevronLeftIcon, ChevronRightIcon } from "lucide-react";
import { useInBrowser } from "@/components/dates";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import { messages } from "@/messages";
import { ShareButton } from "@/features/share/components/share-button";
import type { ShareTarget } from "@/features/share/types";
import type { CardActivity } from "../card-activity";
import { fetchRecentActivity } from "../recent-activity";
import type { Issue } from "../types";
import { StatusIcon, statusLabel } from "../meta";
import { DayIssues } from "./calendar-day-issues";
import { dateKey, issuesByDay, weeksOf, type CalendarIssue, type IssueDates, type Month } from "./calendar-dates";
import { IssueHoverCard, cardOfIssue, type IssueCardData } from "./issue-card";
import { useBarDrag } from "./use-bar-drag";

export type { CalendarIssue, IssueDates } from "./calendar-dates";

const m = messages.issues.views;

/** How long after turning a month the wheel is ignored, so one gesture turns one month. */
const WHEEL_PAUSE_MS = 250;

/** Today in the reader's own time zone. Reads the clock, so it is only called in the browser. */
function today(): { month: Month; key: string } {
  const now = new Date();
  return {
    month: { year: now.getFullYear(), month: now.getMonth() },
    key: dateKey(now.getFullYear(), now.getMonth(), now.getDate()),
  };
}

const MAX_PER_DAY = 3;
/** An issue is drawn on every day of its span, up to this many days. */
const MAX_SPAN_DAYS = 92;

const defaultIssueHref = (issue: CalendarIssue) => `/issue/${issue.key}`;

/**
 * One end of an issue's bar. Dragging it to another day changes the first day (the left end) or
 * the last day (the right end) and leaves the other where it is. A grip shows where to take hold
 * of it when the pointer is over the bar, and stays while that end is being dragged.
 */
function BarEdge({
  edge,
  label,
  active,
  onPress,
}: {
  edge: "start" | "end";
  label: string;
  active: boolean;
  onPress: (event: React.PointerEvent) => void;
}) {
  return (
    <span
      title={label}
      onPointerDown={(event) => {
        // Not the bar's own drag, which moves the whole span.
        event.stopPropagation();
        onPress(event);
      }}
      // The bar is a link; pressing its end must not open the issue.
      onClick={(event) => event.preventDefault()}
      className={cn("group/edge absolute inset-y-0 z-10 flex w-2.5 cursor-ew-resize items-center justify-center", edge === "start" ? "left-0" : "right-0")}
    >
      <span
        className={cn(
          "h-3 w-0.5 rounded-full bg-primary opacity-0 group-hover/bar:opacity-60 group-hover/edge:opacity-100",
          active && "opacity-100",
        )}
      />
    </span>
  );
}

/**
 * Issues on a month calendar: on their due date, or on every day from their start to their due
 * date. Dragging an issue moves its dates by as many days as the pointer moves, and dragging one
 * end of its bar changes only its first or last day; the bar follows the pointer meanwhile and is
 * saved when it is let go. Clicking an empty part of a day, or dragging across days, starts a new
 * issue with those dates. A day with more than three issues says how many more, and that opens a
 * list of all of them. Issues without a due date cannot be placed; how many there are is said
 * under the calendar.
 *
 * `onMove` and `onCreate` are left out where the person may not change or make issues.
 *
 * With `cardOf` a bar has a card that opens when the pointer rests on it (D-201): its title, properties, project, requester and
 * last history entries, in place of the browser's own tooltip. The entries come with the card, or from `loadActivity`.
 *
 * The same calendar is the shared page (docs/features/sharing.md): `fixedMonth` shows one month
 * and nothing turns it, `issueHref` returning null draws the issues as bars that open nothing (the
 * page is read without signing in), and `share` adds the button that shares the month on show.
 */
export function IssueCalendarView<T extends CalendarIssue = CalendarIssue>({
  issues,
  canMove,
  onMove,
  onCreate,
  fixedMonth,
  issueHref = defaultIssueHref,
  share,
  cardOf,
  loadActivity,
}: {
  issues: T[];
  /** Whether this person may change the dates of an issue. */
  canMove?: (issue: T) => boolean;
  onMove?: (issue: T, dates: IssueDates) => void;
  onCreate?: (dates: IssueDates) => void;
  /** Show this month only, without the buttons and the wheel that turn months, and leave out the days of the months around it. */
  fixedMonth?: Month;
  /** Where an issue opens; null for an issue that opens nothing. */
  issueHref?: (issue: T) => string | null;
  /** What to share for a month (YYYY-MM): puts a "공유하기" button in the top line. */
  share?: (month: string) => ShareTarget;
  /** What the card over an issue says; without it a bar has the browser's own tooltip. */
  cardOf?: (issue: T) => IssueCardData;
  /** The last history entries of an issue, for the card, when `cardOf` does not carry them. */
  loadActivity?: (issue: T) => Promise<CardActivity[]>;
}) {
  // The current month depends on the clock, which only the browser may read while rendering.
  const inBrowser = useInBrowser();
  const [chosen, setChosen] = useState<Month | null>(null);
  // The bar being dragged, drawn at the dates it is dragged to.
  const { drag, press } = useBarDrag(issues, onMove);
  // The span being marked for a new issue.
  const [marking, setMarking] = useState<{ from: string; to: string } | null>(null);
  const markingRef = useRef(marking);
  const onCreateRef = useRef(onCreate);
  useEffect(() => {
    markingRef.current = marking;
    onCreateRef.current = onCreate;
  });
  // The span is finished wherever the button is released, even outside the calendar.
  useEffect(() => {
    const finish = () => {
      const span = markingRef.current;
      if (!span) return;
      setMarking(null);
      const [first, last] = span.from <= span.to ? [span.from, span.to] : [span.to, span.from];
      onCreateRef.current?.({ startDate: first === last ? null : first, dueDate: last });
    };
    window.addEventListener("mouseup", finish);
    return () => window.removeEventListener("mouseup", finish);
  }, []);

  // The wheel over the calendar turns the months: down for the next, up for the one before. The
  // listener is added by hand because the page must not scroll with it, and React's own wheel
  // handler may not prevent that.
  const gridRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const grid = gridRef.current;
    if (!grid || fixedMonth) return;
    let lastTurn = 0;
    const onWheel = (event: WheelEvent) => {
      // Zooming (Ctrl) and sideways scrolling of a calendar wider than the screen stay as they are.
      if (event.ctrlKey || Math.abs(event.deltaX) > Math.abs(event.deltaY) || event.deltaY === 0) return;
      event.preventDefault();
      // One month per notch; a touchpad sends a stream of small moves for one gesture.
      if (event.timeStamp - lastTurn < WHEEL_PAUSE_MS) return;
      lastTurn = event.timeStamp;
      const step = event.deltaY > 0 ? 1 : -1;
      setChosen((current) => {
        const from = current ?? today().month;
        const next = new Date(from.year, from.month + step, 1);
        return { year: next.getFullYear(), month: next.getMonth() };
      });
    };
    grid.addEventListener("wheel", onWheel, { passive: false });
    return () => grid.removeEventListener("wheel", onWheel);
  }, [inBrowser, fixedMonth]);

  if (!inBrowser) return <div className="h-96" aria-hidden="true" />;

  const now = today();
  const month = fixedMonth ?? chosen ?? now.month;
  const move = (step: number) => {
    const next = new Date(month.year, month.month + step, 1);
    setChosen({ year: next.getFullYear(), month: next.getMonth() });
  };
  // The bar being dragged is drawn on the days it would have, so it follows the pointer.
  const shown = drag?.dates ? issues.map((issue) => (issue.number === drag.number ? { ...issue, ...drag.dates } : issue)) : issues;
  // Only the six weeks that are on the screen are worked out: an issue of a few months is not taken apart into all its days.
  const weeks = weeksOf(month);
  const byDay = issuesByDay(shown, MAX_SPAN_DAYS, { first: weeks[0][0].key, last: weeks[weeks.length - 1][6].key });
  // The issues drawn on a day: the first few. A bar being dragged is always among them, or it would
  // disappear into "+N개 더" on the days where it comes late, and the drag could not be seen.
  const visibleOf = (items: T[]) => {
    const visible = items.slice(0, MAX_PER_DAY);
    const dragged = drag ? items.findIndex((issue) => issue.number === drag.number) : -1;
    if (dragged >= MAX_PER_DAY) visible[MAX_PER_DAY - 1] = items[dragged];
    return visible;
  };
  // The issues that have no date cannot be on a day: they wait under the calendar to be dragged onto one (D-205). The shared page, which has a
  // month of its own and no issue without a date, has no such place.
  const undated = fixedMonth ? [] : issues.filter((issue) => !issue.dueDate);
  const marked = (key: string) =>
    marking !== null && key >= (marking.from <= marking.to ? marking.from : marking.to) && key <= (marking.from <= marking.to ? marking.to : marking.from);

  return (
    <div className="flex flex-col gap-2 px-4 pb-6">
      <div className="flex flex-wrap items-center gap-1">
        <h2 className="mr-2 text-[0.9375rem] leading-[1.375rem] font-semibold tabular-nums">{m.monthTitle(month.year, month.month + 1)}</h2>
        {fixedMonth ? null : (
          <>
            <Button type="button" variant="ghost" size="icon-sm" className="max-md:size-11" aria-label={m.previousMonth} onClick={() => move(-1)}>
              <ChevronLeftIcon />
            </Button>
            <Button type="button" variant="ghost" size="icon-sm" className="max-md:size-11" aria-label={m.nextMonth} onClick={() => move(1)}>
              <ChevronRightIcon />
            </Button>
            <Button type="button" variant="ghost" size="sm" className="text-muted-foreground max-md:h-11" onClick={() => setChosen(null)}>
              {m.today}
            </Button>
          </>
        )}
        {share ? <ShareButton target={share(`${month.year}-${String(month.month + 1).padStart(2, "0")}`)} /> : null}
        {onCreate || onMove ? <p className="ml-auto text-xs leading-4 text-muted-foreground max-md:hidden">{m.calendarHint}</p> : null}
      </div>
      <div ref={gridRef} className="overflow-x-auto">
        <table
          className={cn(
            "w-full min-w-[42rem] table-fixed border-collapse text-[0.8125rem] leading-5 select-none",
            // One cursor all over while a bar is dragged, whatever the pointer is over.
            drag && !drag.settled && (drag.mode === "start" || drag.mode === "end" ? "[&_*]:cursor-ew-resize!" : "[&_*]:cursor-grabbing!"),
          )}
        >
          <thead>
            <tr>
              {m.weekdays.map((weekday) => (
                <th key={weekday} scope="col" className="border px-2 py-1 text-left font-medium text-muted-foreground">
                  {weekday}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {weeks.map((week) => (
              <tr key={week[0].key}>
                {week.map((cell) => {
                  // The days of the months around a fixed month are left empty: only that month is shown.
                  const items = fixedMonth && !cell.inMonth ? [] : (byDay.get(cell.key) ?? []);
                  return (
                    <td
                      key={cell.key}
                      data-day={cell.key}
                      className={cn("h-28 border p-1 align-top", cell.inMonth ? null : "bg-muted/40", onCreate && "cursor-cell", marked(cell.key) && "bg-primary/10")}
                      onMouseDown={(event) => {
                        // Only the empty part of a day starts a new issue; an issue in it is opened or dragged, and
                        // what a button of the day opens (its list of issues, drawn elsewhere in the page) is not
                        // the day's. React sends events from there up to this cell too, so it is the target that tells.
                        const target = event.target as Element;
                        if (!onCreate || event.button !== 0 || !event.currentTarget.contains(target) || target.closest("a, button")) return;
                        setMarking({ from: cell.key, to: cell.key });
                      }}
                      onMouseEnter={() => setMarking((current) => (current ? { ...current, to: cell.key } : current))}
                    >
                      <div
                        className={cn(
                          "mb-1 flex size-6 items-center justify-center rounded-full text-xs tabular-nums",
                          cell.key === now.key ? "bg-primary font-semibold text-primary-foreground" : cell.inMonth ? null : "text-muted-foreground",
                        )}
                      >
                        {cell.day}
                      </div>
                      <ul className="flex flex-col gap-0.5">
                        {visibleOf(items).map((issue) => {
                          const movable = Boolean(onMove) && (canMove?.(issue) ?? true);
                          const dates = issue.startDate ? `${issue.startDate} ~ ${issue.dueDate}` : issue.dueDate;
                          const dragged = drag?.number === issue.number ? drag : null;
                          const first = cell.key === (issue.startDate ?? issue.dueDate);
                          const last = cell.key === issue.dueDate;
                          const href = issueHref(issue);
                          const title = `${issue.key} ${issue.title} · ${statusLabel(issue.status)} · ${dates}`;
                          const className = cn(
                            "group/bar relative flex h-6 items-center gap-1 rounded-md bg-muted px-1.5 text-xs leading-4 outline-none focus-visible:ring-3 focus-visible:ring-ring/50",
                            href && "hover:bg-accent",
                            // A span reads as one bar: its inner days are joined to their neighbours.
                            issue.startDate && !first && "-ml-1 rounded-l-none",
                            issue.startDate && !last && "-mr-1 rounded-r-none",
                            issue.startDate && "bg-primary/15",
                            issue.startDate && href && "hover:bg-primary/25",
                            (issue.status === "DONE" || issue.status === "CANCELED") && "text-muted-foreground line-through",
                            movable && "cursor-grab",
                            // The bar as it is dragged: outlined all round, as one bar over the days it covers.
                            dragged && "border-y border-primary bg-primary/25 shadow-sm hover:bg-primary/25",
                            dragged && first && "border-l",
                            dragged && last && "border-r",
                          );
                          const card = cardOf?.(issue);
                          const body = (
                            <>
                              <StatusIcon status={issue.status} className="size-3" />
                              <span className="truncate">{issue.title}</span>
                              {movable && first ? (
                                <BarEdge
                                  edge="start"
                                  label={m.resizeStart}
                                  active={dragged?.mode === "start"}
                                  onPress={(event) => press(event, issue, "start", cell.key)}
                                />
                              ) : null}
                              {movable && last ? (
                                <BarEdge
                                  edge="end"
                                  label={m.resizeEnd}
                                  active={dragged?.mode === "end"}
                                  onPress={(event) => press(event, issue, "end", cell.key)}
                                />
                              ) : null}
                            </>
                          );
                          // With a card the browser's own tooltip would say the same thing twice.
                          const element = href ? (
                            <Link
                              href={href}
                              // Dragging a bar is the calendar's own (use-bar-drag.ts), not the browser's drag of a link.
                              draggable={false}
                              onPointerDown={movable ? (event) => press(event, issue, "move", cell.key) : undefined}
                              title={card ? undefined : title}
                              className={className}
                            />
                          ) : (
                            <div title={card ? undefined : title} className={className} />
                          );
                          return (
                            <li key={issue.key}>
                              {card ? (
                                // No card over a bar that is being dragged, or while any is.
                                <IssueHoverCard render={element} card={card} load={loadActivity ? () => loadActivity(issue) : undefined} disabled={drag !== null}>
                                  {body}
                                </IssueHoverCard>
                              ) : (
                                cloneElement(element, undefined, body)
                              )}
                            </li>
                          );
                        })}
                        {items.length > MAX_PER_DAY ? (
                          <li>
                            <DayIssues
                              dayKey={cell.key}
                              issues={items}
                              hidden={items.length - MAX_PER_DAY}
                              issueHref={issueHref}
                              cardOf={cardOf}
                              loadActivity={loadActivity}
                            />
                          </li>
                        ) : null}
                      </ul>
                    </td>
                  );
                })}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {undated.length > 0 ? (
        <section aria-label={m.undatedTitle(undated.length)} className="flex flex-col gap-2 rounded-lg border px-3 py-2.5">
          <div className="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-0.5">
            <h3 className="text-[0.8125rem] leading-5 font-medium">{m.undatedTitle(undated.length)}</h3>
            {onMove ? <p className="text-xs leading-4 text-muted-foreground">{m.undatedHint}</p> : null}
          </div>
          <ul className={cn("flex max-h-44 flex-wrap gap-1.5 overflow-y-auto", drag?.mode === "place" && !drag.settled && "[&_*]:cursor-grabbing!")}>
            {undated.map((issue) => {
              const movable = Boolean(onMove) && (canMove?.(issue) ?? true);
              const href = issueHref(issue);
              const card = cardOf?.(issue);
              const closed = issue.status === "DONE" || issue.status === "CANCELED";
              const className = cn(
                "flex h-6 max-w-72 items-center gap-1 rounded-md bg-muted px-1.5 text-xs leading-4 outline-none focus-visible:ring-3 focus-visible:ring-ring/50",
                href && "hover:bg-accent",
                closed && "text-muted-foreground line-through",
                movable && "cursor-grab",
                // The one being dragged stays here, dimmed, while its bar is on the day it is over.
                drag?.number === issue.number && drag.mode === "place" && "opacity-50",
              );
              const element = href ? (
                <Link
                  href={href}
                  draggable={false}
                  onPointerDown={movable ? (event) => press(event, issue, "place", "") : undefined}
                  title={card ? undefined : `${issue.key} ${issue.title} · ${statusLabel(issue.status)}`}
                  className={className}
                />
              ) : (
                <div title={card ? undefined : `${issue.key} ${issue.title}`} className={className} />
              );
              const body = (
                <>
                  <StatusIcon status={issue.status} className="size-3" />
                  <span className="shrink-0 text-muted-foreground tabular-nums">{issue.key}</span>
                  <span className="truncate">{issue.title}</span>
                </>
              );
              return (
                <li key={issue.key} className="min-w-0">
                  {card ? (
                    <IssueHoverCard render={element} card={card} load={loadActivity ? () => loadActivity(issue) : undefined} disabled={drag !== null}>
                      {body}
                    </IssueHoverCard>
                  ) : (
                    cloneElement(element, undefined, body)
                  )}
                </li>
              );
            })}
          </ul>
        </section>
      ) : null}
    </div>
  );
}

/**
 * The calendar of the lists of issues the signed-in person reads: each bar has its card, and the last entries of the
 * issue's history are read from the server when the card first opens (D-201).
 */
export function IssuesCalendarView(props: Omit<React.ComponentProps<typeof IssueCalendarView<Issue>>, "cardOf" | "loadActivity">) {
  return <IssueCalendarView<Issue> {...props} cardOf={cardOfIssue} loadActivity={(issue) => fetchRecentActivity(issue.number)} />;
}
