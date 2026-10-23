/** The dates of an issue: one day (`startDate` null) or a first and a last day. */
export type IssueDates = { startDate: string | null; dueDate: string };

/** What dragging a bar changes: both its ends together, or only its first or its last day. */
export type BarDragMode = "move" | "start" | "end";

export type Month = { year: number; month: number };

const DAY = 86_400_000;
const pad = (value: number) => String(value).padStart(2, "0");

/** `month` counts from 0, as in `Date`. */
export const dateKey = (year: number, month: number, day: number) => `${year}-${pad(month + 1)}-${pad(day)}`;
/** A YYYY-MM-DD day as a count of days, and back; for moving dates by a number of days. */
export const toDays = (key: string) => Math.round(Date.UTC(Number(key.slice(0, 4)), Number(key.slice(5, 7)) - 1, Number(key.slice(8, 10))) / DAY);
export const fromDays = (days: number) => new Date(days * DAY).toISOString().slice(0, 10);

/** The month, day and weekday (0 for Sunday) of a YYYY-MM-DD day. */
export function dayParts(key: string): { month: number; day: number; weekday: number } {
  const month = Number(key.slice(5, 7));
  const day = Number(key.slice(8, 10));
  return { month, day, weekday: new Date(Number(key.slice(0, 4)), month - 1, day).getDay() };
}

/** The days shown for a month: whole weeks from Sunday, so the days around the month fill the corners. */
export function weeksOf({ year, month }: Month): { key: string; day: number; inMonth: boolean }[][] {
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

/** Whether two spans cover the same days. A span of one day is the same with or without a `startDate`. */
export function sameDates(a: IssueDates, b: IssueDates): boolean {
  return (a.startDate ?? a.dueDate) === (b.startDate ?? b.dueDate) && a.dueDate === b.dueDate;
}

/**
 * The dates an issue has while its bar is dragged from the day `anchor` (where the pointer went
 * down) to the day `pointer` is over now.
 *
 * Moving shifts both ends by the same number of days, so the span keeps its length. Dragging one
 * end puts that end on the pointer's day and leaves the other; an end cannot pass the other one
 * (the span is then the one day they meet on), and a span of one day has no `startDate`.
 */
export function draggedDates(origin: IssueDates, mode: BarDragMode, anchor: string, pointer: string): IssueDates {
  if (mode === "move") {
    const shift = toDays(pointer) - toDays(anchor);
    return {
      startDate: origin.startDate ? fromDays(toDays(origin.startDate) + shift) : null,
      dueDate: fromDays(toDays(origin.dueDate) + shift),
    };
  }
  const first = origin.startDate ?? origin.dueDate;
  const [start, end] = mode === "start" ? [pointer < origin.dueDate ? pointer : origin.dueDate, origin.dueDate] : [first, pointer > first ? pointer : first];
  return start === end ? { startDate: null, dueDate: end } : { startDate: start, dueDate: end };
}

/** The days an issue is drawn on: from its first to its last day, but no more than `maxDays` before the last. */
export function spanDays({ startDate, dueDate }: IssueDates, maxDays: number): string[] {
  const last = toDays(dueDate);
  const first = Math.max(startDate ? toDays(startDate) : last, last - maxDays);
  return Array.from({ length: last - first + 1 }, (_, index) => fromDays(first + index));
}

/** The issues of each day they are drawn on, in the order given. Issues without a due date are left out. */
export function issuesByDay<T extends { startDate: string | null; dueDate: string | null }>(issues: T[], maxDays: number): Map<string, T[]> {
  const byDay = new Map<string, T[]>();
  for (const issue of issues) {
    if (!issue.dueDate) continue;
    for (const key of spanDays({ startDate: issue.startDate, dueDate: issue.dueDate }, maxDays)) {
      const day = byDay.get(key);
      if (day) day.push(issue);
      else byDay.set(key, [issue]);
    }
  }
  return byDay;
}
