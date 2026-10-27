import { describe, expect, it } from "vitest";
import { addDays, matchesDue } from "./due-filter";
import type { Issue } from "./types";

const issue = (dueDate: string | null, status: Issue["status"] = "READY") => ({ dueDate, status }) as Issue;
const TODAY = "2026-10-27";

describe("matchesDue", () => {
  it("takes the issues without a due date for none, whatever day it is", () => {
    expect(matchesDue(issue(null), "none", TODAY)).toBe(true);
    expect(matchesDue(issue(null), "none", null)).toBe(true);
    expect(matchesDue(issue("2026-10-27"), "none", TODAY)).toBe(false);
  });

  it("splits the dated issues into overdue, due today and due within a week", () => {
    expect(matchesDue(issue("2026-10-26"), "overdue", TODAY)).toBe(true);
    expect(matchesDue(issue("2026-10-27"), "overdue", TODAY)).toBe(false);
    expect(matchesDue(issue("2026-10-27"), "today", TODAY)).toBe(true);
    expect(matchesDue(issue("2026-10-28"), "today", TODAY)).toBe(false);
    expect(matchesDue(issue("2026-10-27"), "week", TODAY)).toBe(true);
    expect(matchesDue(issue("2026-11-03"), "week", TODAY)).toBe(true);
    expect(matchesDue(issue("2026-11-04"), "week", TODAY)).toBe(false);
    expect(matchesDue(issue("2026-10-26"), "week", TODAY)).toBe(false);
  });

  it("leaves out issues without a date and finished ones when it asks about time", () => {
    expect(matchesDue(issue(null), "overdue", TODAY)).toBe(false);
    expect(matchesDue(issue("2026-10-20", "DONE"), "overdue", TODAY)).toBe(false);
    expect(matchesDue(issue("2026-10-27", "CANCELED"), "today", TODAY)).toBe(false);
  });

  it("leaves nobody out until the browser knows the day", () => {
    expect(matchesDue(issue("2026-10-01"), "today", null)).toBe(true);
  });
});

describe("addDays", () => {
  it("goes over the end of a month and a year", () => {
    expect(addDays("2026-10-27", 7)).toBe("2026-11-03");
    expect(addDays("2026-12-28", 7)).toBe("2027-01-04");
  });
});
