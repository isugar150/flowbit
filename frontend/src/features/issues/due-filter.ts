import type { Issue } from "./types";

/** What the due-date filter of a list can be set to besides "every date" and "none" (`?due=`). */
export const DUE_FILTERS = ["overdue", "today", "week"] as const;
export type DueFilter = (typeof DUE_FILTERS)[number];
/** The filter value for issues without a due date; `?due=none`. */
export const NO_DUE = "none";
/** How far ahead "week" looks: today and the days after it up to this many, as in the daily summary. */
const WEEK_DAYS = 7;

/** `day` (YYYY-MM-DD) moved by `days`, as the same kind of text. */
export function addDays(day: string, days: number): string {
  const [year, month, date] = day.split("-").map(Number);
  return new Date(Date.UTC(year, month - 1, date + days)).toISOString().slice(0, 10);
}

/**
 * Whether an issue passes the due-date filter. "none" is the issues without a due date. The others look at issues that
 * are not finished, since a finished issue is not due any more: overdue (before today), due today, due within a week
 * (today to seven days ahead). `today` is YYYY-MM-DD in the browser's time zone, null while the server renders (which
 * may not read the clock): nothing is left out until the browser knows the day.
 */
export function matchesDue(issue: Issue, filter: string, today: string | null): boolean {
  if (filter === NO_DUE) return issue.dueDate === null;
  if (today === null) return true;
  if (issue.dueDate === null || issue.status === "DONE" || issue.status === "CANCELED") return false;
  switch (filter) {
    case "overdue":
      return issue.dueDate < today;
    case "today":
      return issue.dueDate === today;
    case "week":
      return issue.dueDate >= today && issue.dueDate <= addDays(today, WEEK_DAYS);
    default:
      return true;
  }
}
