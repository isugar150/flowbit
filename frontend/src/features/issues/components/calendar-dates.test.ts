import { describe, expect, it } from "vitest";
import { dateKey, dayParts, draggedDates, fromDays, issuesByDay, sameDates, spanDays, toDays, weeksOf } from "./calendar-dates";

describe("days", () => {
  it("counts days and turns the count back into a day", () => {
    expect(toDays("2026-10-24") - toDays("2026-10-23")).toBe(1);
    expect(fromDays(toDays("2026-10-23"))).toBe("2026-10-23");
    // Across the end of a month and of a year.
    expect(fromDays(toDays("2026-10-31") + 1)).toBe("2026-11-01");
    expect(fromDays(toDays("2026-12-31") + 1)).toBe("2027-01-01");
  });

  it("writes a day with its month counted from 0", () => {
    expect(dateKey(2026, 9, 5)).toBe("2026-10-05");
  });

  it("reads the month, day and weekday of a day", () => {
    // 2026-10-23 is a Friday.
    expect(dayParts("2026-10-23")).toEqual({ month: 10, day: 23, weekday: 5 });
    expect(dayParts("2026-11-01")).toEqual({ month: 11, day: 1, weekday: 0 });
  });
});

describe("weeksOf", () => {
  it("fills whole weeks from Sunday around the month", () => {
    const weeks = weeksOf({ year: 2026, month: 9 });
    expect(weeks).toHaveLength(5);
    expect(weeks.every((week) => week.length === 7)).toBe(true);
    expect(weeks[0][0]).toEqual({ key: "2026-09-27", day: 27, inMonth: false });
    expect(weeks[0][4]).toEqual({ key: "2026-10-01", day: 1, inMonth: true });
    expect(weeks[4][6]).toEqual({ key: "2026-10-31", day: 31, inMonth: true });
  });
});

describe("draggedDates", () => {
  const span = { startDate: "2026-10-12", dueDate: "2026-10-14" };
  const single = { startDate: null, dueDate: "2026-10-15" };

  it("moves a single day to the day the pointer is over", () => {
    expect(draggedDates(single, "move", "2026-10-15", "2026-10-16")).toEqual({ startDate: null, dueDate: "2026-10-16" });
    expect(draggedDates(single, "move", "2026-10-15", "2026-10-08")).toEqual({ startDate: null, dueDate: "2026-10-08" });
  });

  it("moves a span by as many days as the pointer moved, whichever of its days was taken", () => {
    // Taken by its middle day and dropped two days later: both ends move two days.
    expect(draggedDates(span, "move", "2026-10-13", "2026-10-15")).toEqual({ startDate: "2026-10-14", dueDate: "2026-10-16" });
    // A move across weeks and months keeps the length.
    expect(draggedDates(span, "move", "2026-10-12", "2026-11-02")).toEqual({ startDate: "2026-11-02", dueDate: "2026-11-04" });
  });

  it("leaves the dates as they were when the pointer is back on the day it started on", () => {
    expect(draggedDates(span, "move", "2026-10-13", "2026-10-13")).toEqual(span);
    expect(draggedDates(span, "start", "2026-10-12", "2026-10-12")).toEqual(span);
    expect(draggedDates(span, "end", "2026-10-14", "2026-10-14")).toEqual(span);
    expect(draggedDates(single, "move", "2026-10-15", "2026-10-15")).toEqual(single);
  });

  it("changes only the first day when its end is dragged", () => {
    expect(draggedDates(span, "start", "2026-10-12", "2026-10-09")).toEqual({ startDate: "2026-10-09", dueDate: "2026-10-14" });
    expect(draggedDates(span, "start", "2026-10-12", "2026-10-13")).toEqual({ startDate: "2026-10-13", dueDate: "2026-10-14" });
  });

  it("changes only the last day when its end is dragged", () => {
    expect(draggedDates(span, "end", "2026-10-14", "2026-10-20")).toEqual({ startDate: "2026-10-12", dueDate: "2026-10-20" });
    expect(draggedDates(span, "end", "2026-10-14", "2026-10-13")).toEqual({ startDate: "2026-10-12", dueDate: "2026-10-13" });
  });

  it("makes a span of a day an ordinary single day without a first day", () => {
    expect(draggedDates(span, "start", "2026-10-12", "2026-10-14")).toEqual({ startDate: null, dueDate: "2026-10-14" });
    expect(draggedDates(span, "end", "2026-10-14", "2026-10-12")).toEqual({ startDate: null, dueDate: "2026-10-12" });
  });

  it("stops an end at the other end instead of passing it", () => {
    expect(draggedDates(span, "start", "2026-10-12", "2026-10-20")).toEqual({ startDate: null, dueDate: "2026-10-14" });
    expect(draggedDates(span, "end", "2026-10-14", "2026-10-01")).toEqual({ startDate: null, dueDate: "2026-10-12" });
  });

  it("grows a single day into a span from either end", () => {
    expect(draggedDates(single, "start", "2026-10-15", "2026-10-13")).toEqual({ startDate: "2026-10-13", dueDate: "2026-10-15" });
    expect(draggedDates(single, "end", "2026-10-15", "2026-10-18")).toEqual({ startDate: "2026-10-15", dueDate: "2026-10-18" });
  });
});

describe("sameDates", () => {
  it("compares the days covered", () => {
    expect(sameDates({ startDate: "2026-10-12", dueDate: "2026-10-14" }, { startDate: "2026-10-12", dueDate: "2026-10-14" })).toBe(true);
    expect(sameDates({ startDate: "2026-10-12", dueDate: "2026-10-14" }, { startDate: "2026-10-13", dueDate: "2026-10-14" })).toBe(false);
    expect(sameDates({ startDate: "2026-10-12", dueDate: "2026-10-14" }, { startDate: "2026-10-12", dueDate: "2026-10-15" })).toBe(false);
  });

  it("takes a single day with or without a first day as the same", () => {
    expect(sameDates({ startDate: null, dueDate: "2026-10-15" }, { startDate: "2026-10-15", dueDate: "2026-10-15" })).toBe(true);
    expect(sameDates({ startDate: null, dueDate: "2026-10-15" }, { startDate: null, dueDate: "2026-10-16" })).toBe(false);
  });
});

describe("spanDays", () => {
  it("lists every day from the first to the last", () => {
    expect(spanDays({ startDate: "2026-10-30", dueDate: "2026-11-02" }, 92)).toEqual(["2026-10-30", "2026-10-31", "2026-11-01", "2026-11-02"]);
  });

  it("lists the one day of an issue without a first day", () => {
    expect(spanDays({ startDate: null, dueDate: "2026-10-15" }, 92)).toEqual(["2026-10-15"]);
  });

  it("leaves out the days before the last maxDays", () => {
    const days = spanDays({ startDate: "2026-01-01", dueDate: "2026-10-15" }, 92);
    expect(days).toHaveLength(93);
    expect(days[days.length - 1]).toBe("2026-10-15");
  });
});

describe("issuesByDay", () => {
  it("puts an issue on every day it covers, in the order the issues come", () => {
    const one = { id: "a", startDate: "2026-10-12", dueDate: "2026-10-14" };
    const two = { id: "b", startDate: null, dueDate: "2026-10-13" };
    const byDay = issuesByDay([one, two], 92);
    expect(byDay.get("2026-10-11")).toBeUndefined();
    expect(byDay.get("2026-10-12")).toEqual([one]);
    expect(byDay.get("2026-10-13")).toEqual([one, two]);
    expect(byDay.get("2026-10-14")).toEqual([one]);
  });

  it("leaves out an issue that has no due date", () => {
    expect(issuesByDay([{ startDate: null, dueDate: null }], 92).size).toBe(0);
  });
});
