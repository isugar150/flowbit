const DAY = 86_400_000;

/** A calendar day (YYYY-MM-DD) as a number of days, so days can be subtracted. */
export const dayOf = (date: string) => Math.floor(Date.UTC(Number(date.slice(0, 4)), Number(date.slice(5, 7)) - 1, Number(date.slice(8, 10))) / DAY);

/** The reader's own calendar day of a moment. */
export function localDay(moment: string | number): number {
  const date = new Date(moment);
  return Math.floor(Date.UTC(date.getFullYear(), date.getMonth(), date.getDate()) / DAY);
}

/** What the timeline needs of an issue to know the days it covers. */
export type SpanIssue = {
  status: string;
  startDate: string | null;
  dueDate: string | null;
  createdAt: string;
  updatedAt: string;
};

/**
 * The days a timeline bar covers (D-207), as day numbers, the first and the last, and whether the end is only a guess (`open`).
 *
 * With a due date the bar ends there, and begins on the start date; **an issue with only a due date is that one day**, as the
 * calendar draws it. (It used to begin on the day the issue was made: a day's work written down afterwards, when the due date
 * is before the day the issue was made, then ran from that day to the day it was made, which is often today.) Without a due
 * date the end is not known, so the bar begins on the start date or the day the issue was made, and a finished issue ends when
 * it was last changed and an unfinished one reaches `today`, drawn lighter.
 */
export function spanOf(issue: SpanIssue, today: number): { start: number; end: number; open: boolean } {
  const finished = issue.status === "DONE" || issue.status === "CANCELED";
  const start = issue.startDate ? dayOf(issue.startDate) : issue.dueDate ? dayOf(issue.dueDate) : localDay(issue.createdAt);
  const end = issue.dueDate ? dayOf(issue.dueDate) : finished ? localDay(issue.updatedAt) : today;
  // A span that was written the wrong way round is drawn the right way round.
  return { start: Math.min(start, end), end: Math.max(start, end), open: !issue.dueDate && !finished };
}
