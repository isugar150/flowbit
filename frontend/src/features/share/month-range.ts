/** A stretch of months, the last one included: both ends as `YYYY-MM`. */
export type MonthRange = { from: string; to: string };

/** The most months one shared timeline covers (the server refuses more, D-199). */
export const MAX_RANGE_MONTHS = 24;

/** A `YYYY-MM` month as a count of months, for adding and subtracting. */
const monthNumber = (month: string) => Number(month.slice(0, 4)) * 12 + Number(month.slice(5, 7)) - 1;
const monthOfNumber = (count: number) => `${String(Math.floor(count / 12)).padStart(4, "0")}-${String((count % 12) + 1).padStart(2, "0")}`;

/** The month `by` months after (before, when negative) `month`. */
export function shiftMonth(month: string, by: number): string {
  return monthOfNumber(monthNumber(month) + by);
}

/** How many months the range covers, both ends counted. */
export function monthsIn(range: MonthRange): number {
  return monthNumber(range.to) - monthNumber(range.from) + 1;
}

/** The month a date falls in, by the reader's own calendar. */
export function monthOfDate(date: Date): string {
  return `${String(date.getFullYear()).padStart(4, "0")}-${String(date.getMonth() + 1).padStart(2, "0")}`;
}

/** What the share window starts with: this month and the two after it. */
export function defaultRange(thisMonth: string): MonthRange {
  return { from: thisMonth, to: shiftMonth(thisMonth, 2) };
}

/** Every month from `first` to `last`, in order. */
export function monthsFromTo(first: string, last: string): string[] {
  const count = Math.max(0, monthNumber(last) - monthNumber(first) + 1);
  return Array.from({ length: count }, (_, index) => shiftMonth(first, index));
}

/**
 * The months the two ends can be picked from: three years either side of this month for the first, and as far after the
 * first's last month as the range may be long for the last, so that moving the first end never leaves the last one
 * with no month to show.
 */
export function pickableMonths(thisMonth: string): { from: string[]; to: string[] } {
  const first = shiftMonth(thisMonth, -36);
  const lastFrom = shiftMonth(thisMonth, 36);
  return { from: monthsFromTo(first, lastFrom), to: monthsFromTo(first, shiftMonth(lastFrom, MAX_RANGE_MONTHS - 1)) };
}

/**
 * The range after one end is set to `month`. The other end goes along when the two would cross, or
 * when the range would be longer than [MAX_RANGE_MONTHS], so that a range which cannot be shared
 * is never on the screen.
 */
export function rangeAfter(range: MonthRange, end: "from" | "to", month: string): MonthRange {
  const picked = monthNumber(month);
  if (end === "from") {
    const to = Math.min(Math.max(monthNumber(range.to), picked), picked + MAX_RANGE_MONTHS - 1);
    return { from: month, to: monthOfNumber(to) };
  }
  const from = Math.max(Math.min(monthNumber(range.from), picked), picked - MAX_RANGE_MONTHS + 1);
  return { from: monthOfNumber(from), to: month };
}
