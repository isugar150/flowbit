import { describe, expect, it } from "vitest";
import { dayOf, localDay, spanOf, type SpanIssue } from "./timeline-span";

const issue = (change: Partial<SpanIssue>): SpanIssue => ({
  status: "IN_PROGRESS",
  startDate: null,
  dueDate: null,
  createdAt: "2026-10-23T12:00:00Z",
  updatedAt: "2026-10-23T12:00:00Z",
  ...change,
});

const today = dayOf("2026-10-23");

describe("spanOf", () => {
  it("is the one day of an issue with only a due date, whenever it was made", () => {
    // A day's work written down afterwards: due on the 10th, made today. It is the 10th, not the 10th to today.
    expect(spanOf(issue({ dueDate: "2026-10-10", status: "DONE" }), today)).toEqual({ start: dayOf("2026-10-10"), end: dayOf("2026-10-10"), open: false });
    // The same when the due date comes after the day it was made.
    expect(spanOf(issue({ dueDate: "2026-11-02", createdAt: "2026-10-01T12:00:00Z" }), today)).toEqual({
      start: dayOf("2026-11-02"),
      end: dayOf("2026-11-02"),
      open: false,
    });
  });

  it("runs from the start date to the due date when there are both", () => {
    expect(spanOf(issue({ startDate: "2026-10-05", dueDate: "2026-10-09" }), today)).toEqual({ start: dayOf("2026-10-05"), end: dayOf("2026-10-09"), open: false });
  });

  it("draws a span written the wrong way round the right way round", () => {
    expect(spanOf(issue({ startDate: "2026-10-09", dueDate: "2026-10-05" }), today)).toEqual({ start: dayOf("2026-10-05"), end: dayOf("2026-10-09"), open: false });
  });

  it("runs from the day an issue without dates was made to today, lighter, while it is open", () => {
    const span = spanOf(issue({ createdAt: "2026-10-10T12:00:00Z" }), today);
    expect(span).toEqual({ start: localDay("2026-10-10T12:00:00Z"), end: today, open: true });
  });

  it("ends a finished issue without a due date where it was last changed", () => {
    const span = spanOf(issue({ status: "DONE", createdAt: "2026-10-10T12:00:00Z", updatedAt: "2026-10-15T12:00:00Z" }), today);
    expect(span).toEqual({ start: localDay("2026-10-10T12:00:00Z"), end: localDay("2026-10-15T12:00:00Z"), open: false });
    expect(spanOf(issue({ status: "CANCELED", createdAt: "2026-10-10T12:00:00Z", updatedAt: "2026-10-15T12:00:00Z" }), today).open).toBe(false);
  });
});
