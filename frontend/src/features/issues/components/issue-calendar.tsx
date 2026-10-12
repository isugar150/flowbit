"use client";

import { useEffect, useRef, useState } from "react";
import Link from "next/link";
import { ChevronLeftIcon, ChevronRightIcon } from "lucide-react";
import { useInBrowser } from "@/components/dates";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import { messages } from "@/messages";
import { StatusIcon, statusLabel } from "../meta";
import type { Issue } from "../types";

const m = messages.issues.views;

type Month = { year: number; month: number };
/** The dates of an issue: one day (`startDate` null) or a first and a last day. */
export type IssueDates = { startDate: string | null; dueDate: string };

const DAY = 86_400_000;
const pad = (value: number) => String(value).padStart(2, "0");
const dateKey = (year: number, month: number, day: number) => `${year}-${pad(month + 1)}-${pad(day)}`;
/** A YYYY-MM-DD day as a count of days, and back; for moving dates by a number of days. */
const toDays = (key: string) => Math.round(Date.UTC(Number(key.slice(0, 4)), Number(key.slice(5, 7)) - 1, Number(key.slice(8, 10))) / DAY);
const fromDays = (days: number) => new Date(days * DAY).toISOString().slice(0, 10);

/** Today in the reader's own time zone. Reads the clock, so it is only called in the browser. */
function today(): { month: Month; key: string } {
  const now = new Date();
  return {
    month: { year: now.getFullYear(), month: now.getMonth() },
    key: dateKey(now.getFullYear(), now.getMonth(), now.getDate()),
  };
}

/** The days shown for a month: whole weeks from Sunday, so the days around the month fill the corners. */
function weeksOf({ year, month }: Month): { key: string; day: number; inMonth: boolean }[][] {
  const first = new Date(year, month, 1);
  const cursor = new Date(year, month, 1 - first.getDay());
  const weeks: { key: string; day: number; inMonth: boolean }[][] = [];
  do {
    const week = [];
    for (let weekday = 0; weekday < 7; weekday++) {
      week.push({
        key: dateKey(cursor.getFullYear(), cursor.getMonth(), cursor.getDate()),
        day: cursor.getDate(),
        inMonth: cursor.getMonth() === month,
      });
      cursor.setDate(cursor.getDate() + 1);
    }
    weeks.push(week);
  } while (cursor.getMonth() === month);
  return weeks;
}

const MAX_PER_DAY = 3;
/** An issue is drawn on every day of its span, up to this many days. */
const MAX_SPAN_DAYS = 92;
const DRAG_TYPE = "application/x-flowbit-issue";

/**
 * Issues on a month calendar: on their due date, or on every day from their start to their due
 * date. Dragging an issue to another day moves its dates by that many days. Clicking an empty
 * part of a day, or dragging across days, starts a new issue with those dates. Issues without a
 * due date cannot be placed; how many there are is said under the calendar.
 *
 * `onMove` and `onCreate` are left out where the person may not change or make issues.
 */
export function IssueCalendarView({
  issues,
  canMove,
  onMove,
  onCreate,
}: {
  issues: Issue[];
  /** Whether this person may change the dates of an issue. */
  canMove?: (issue: Issue) => boolean;
  onMove?: (issue: Issue, dates: IssueDates) => void;
  onCreate?: (dates: IssueDates) => void;
}) {
  // The current month depends on the clock, which only the browser may read while rendering.
  const inBrowser = useInBrowser();
  const [chosen, setChosen] = useState<Month | null>(null);
  // The day an issue is being dragged over, and the span being marked for a new issue.
  const [dropDay, setDropDay] = useState<string | null>(null);
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

  if (!inBrowser) return <div className="h-96" aria-hidden="true" />;

  const now = today();
  const month = chosen ?? now.month;
  const move = (step: number) => {
    const next = new Date(month.year, month.month + step, 1);
    setChosen({ year: next.getFullYear(), month: next.getMonth() });
  };
  const byDay = new Map<string, Issue[]>();
  for (const issue of issues) {
    if (!issue.dueDate) continue;
    const last = toDays(issue.dueDate);
    const first = Math.max(issue.startDate ? toDays(issue.startDate) : last, last - MAX_SPAN_DAYS);
    for (let day = first; day <= last; day++) {
      const key = fromDays(day);
      byDay.set(key, [...(byDay.get(key) ?? []), issue]);
    }
  }
  const undated = issues.filter((issue) => !issue.dueDate).length;
  const marked = (key: string) =>
    marking !== null && key >= (marking.from <= marking.to ? marking.from : marking.to) && key <= (marking.from <= marking.to ? marking.to : marking.from);

  const drop = (event: React.DragEvent, key: string) => {
    setDropDay(null);
    const data = event.dataTransfer.getData(DRAG_TYPE);
    if (!data || !onMove) return;
    event.preventDefault();
    const { number, from } = JSON.parse(data) as { number: number; from: string };
    const issue = issues.find((item) => item.number === number);
    const shift = toDays(key) - toDays(from);
    if (!issue?.dueDate || shift === 0) return;
    // The whole span moves together and keeps its length.
    onMove(issue, {
      startDate: issue.startDate ? fromDays(toDays(issue.startDate) + shift) : null,
      dueDate: fromDays(toDays(issue.dueDate) + shift),
    });
  };

  return (
    <div className="flex flex-col gap-2 px-4 pb-6">
      <div className="flex flex-wrap items-center gap-1">
        <h2 className="mr-2 text-[0.9375rem] leading-[1.375rem] font-semibold tabular-nums">{m.monthTitle(month.year, month.month + 1)}</h2>
        <Button type="button" variant="ghost" size="icon-sm" className="max-md:size-11" aria-label={m.previousMonth} onClick={() => move(-1)}>
          <ChevronLeftIcon />
        </Button>
        <Button type="button" variant="ghost" size="icon-sm" className="max-md:size-11" aria-label={m.nextMonth} onClick={() => move(1)}>
          <ChevronRightIcon />
        </Button>
        <Button type="button" variant="ghost" size="sm" className="text-muted-foreground max-md:h-11" onClick={() => setChosen(null)}>
          {m.today}
        </Button>
        {onCreate || onMove ? <p className="ml-auto text-xs leading-4 text-muted-foreground max-md:hidden">{m.calendarHint}</p> : null}
      </div>
      <div className="overflow-x-auto">
        <table className="w-full min-w-[42rem] table-fixed border-collapse text-[0.8125rem] leading-5 select-none">
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
            {weeksOf(month).map((week) => (
              <tr key={week[0].key}>
                {week.map((cell) => {
                  const items = byDay.get(cell.key) ?? [];
                  return (
                    <td
                      key={cell.key}
                      className={cn(
                        "h-28 border p-1 align-top",
                        cell.inMonth ? null : "bg-muted/40",
                        onCreate && "cursor-cell",
                        (marked(cell.key) || dropDay === cell.key) && "bg-primary/10",
                      )}
                      onMouseDown={(event) => {
                        // Only the empty part of a day starts a new issue; an issue in it is opened or dragged.
                        if (!onCreate || event.button !== 0 || (event.target as Element).closest("a")) return;
                        setMarking({ from: cell.key, to: cell.key });
                      }}
                      onMouseEnter={() => setMarking((current) => (current ? { ...current, to: cell.key } : current))}
                      onDragOver={(event) => {
                        if (!onMove || !event.dataTransfer.types.includes(DRAG_TYPE)) return;
                        event.preventDefault();
                        event.dataTransfer.dropEffect = "move";
                        setDropDay(cell.key);
                      }}
                      onDragLeave={() => setDropDay((current) => (current === cell.key ? null : current))}
                      onDrop={(event) => drop(event, cell.key)}
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
                        {items.slice(0, MAX_PER_DAY).map((issue) => {
                          const movable = Boolean(onMove) && (canMove?.(issue) ?? true);
                          const dates = issue.startDate ? `${issue.startDate} ~ ${issue.dueDate}` : issue.dueDate;
                          return (
                            <li key={issue.id}>
                              <Link
                                href={`/issue/${issue.key}`}
                                draggable={movable}
                                onDragStart={(event) => {
                                  event.dataTransfer.setData(DRAG_TYPE, JSON.stringify({ number: issue.number, from: cell.key }));
                                  event.dataTransfer.effectAllowed = "move";
                                }}
                                title={`${issue.key} ${issue.title} · ${statusLabel(issue.status)} · ${dates}`}
                                className={cn(
                                  "flex h-6 items-center gap-1 rounded-md bg-muted px-1.5 text-xs leading-4 outline-none hover:bg-accent focus-visible:ring-3 focus-visible:ring-ring/50",
                                  // A span reads as one bar: its inner days are joined to their neighbours.
                                  issue.startDate && cell.key !== issue.startDate && "-ml-1 rounded-l-none",
                                  issue.startDate && cell.key !== issue.dueDate && "-mr-1 rounded-r-none",
                                  issue.startDate && "bg-primary/15 hover:bg-primary/25",
                                  (issue.status === "DONE" || issue.status === "CANCELED") && "text-muted-foreground line-through",
                                  movable && "cursor-grab active:cursor-grabbing",
                                )}
                              >
                                <StatusIcon status={issue.status} className="size-3" />
                                <span className="truncate">{issue.title}</span>
                              </Link>
                            </li>
                          );
                        })}
                        {items.length > MAX_PER_DAY ? (
                          <li className="px-1.5 text-xs leading-4 text-muted-foreground">{m.more(items.length - MAX_PER_DAY)}</li>
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
      {undated > 0 ? <p className="text-[0.8125rem] leading-5 text-muted-foreground">{m.undated(undated)}</p> : null}
    </div>
  );
}
