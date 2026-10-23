import { describe, expect, it } from "vitest";
import { MAX_RANGE_MONTHS, defaultRange, monthOfDate, monthsFromTo, monthsIn, pickableMonths, rangeAfter, shiftMonth } from "./month-range";

describe("shiftMonth", () => {
  it("moves a month forward and back across the end of a year", () => {
    expect(shiftMonth("2026-10", 2)).toBe("2026-12");
    expect(shiftMonth("2026-11", 3)).toBe("2027-02");
    expect(shiftMonth("2026-02", -3)).toBe("2025-11");
    expect(shiftMonth("2026-10", 0)).toBe("2026-10");
  });
});

describe("monthsIn", () => {
  it("counts both ends", () => {
    expect(monthsIn({ from: "2026-10", to: "2026-10" })).toBe(1);
    expect(monthsIn({ from: "2026-10", to: "2026-12" })).toBe(3);
    expect(monthsIn({ from: "2026-11", to: "2027-02" })).toBe(4);
  });
});

describe("monthOfDate", () => {
  it("writes the month a date falls in", () => {
    expect(monthOfDate(new Date(2026, 9, 23))).toBe("2026-10");
    expect(monthOfDate(new Date(2027, 0, 1))).toBe("2027-01");
  });
});

describe("defaultRange", () => {
  it("is this month and the two after it", () => {
    expect(defaultRange("2026-10")).toEqual({ from: "2026-10", to: "2026-12" });
    expect(defaultRange("2026-12")).toEqual({ from: "2026-12", to: "2027-02" });
  });
});

describe("monthsFromTo", () => {
  it("lists every month between two, both included", () => {
    expect(monthsFromTo("2026-11", "2027-02")).toEqual(["2026-11", "2026-12", "2027-01", "2027-02"]);
    expect(monthsFromTo("2026-10", "2026-10")).toEqual(["2026-10"]);
  });
});

describe("pickableMonths", () => {
  it("lists the months three years either side for the first end, in order", () => {
    const { from } = pickableMonths("2026-10");
    expect(from).toHaveLength(73);
    expect(from[0]).toBe("2023-10");
    expect(from[36]).toBe("2026-10");
    expect(from[72]).toBe("2029-10");
  });

  it("lets the last end reach as far as the longest range from the last first end", () => {
    const { to } = pickableMonths("2026-10");
    expect(to[0]).toBe("2023-10");
    expect(to[to.length - 1]).toBe("2031-09");
  });
});

describe("rangeAfter", () => {
  const range = { from: "2026-10", to: "2026-12" };

  it("sets the end that was picked and keeps the other where they do not conflict", () => {
    expect(rangeAfter(range, "from", "2026-09")).toEqual({ from: "2026-09", to: "2026-12" });
    expect(rangeAfter(range, "from", "2026-11")).toEqual({ from: "2026-11", to: "2026-12" });
    expect(rangeAfter(range, "to", "2027-03")).toEqual({ from: "2026-10", to: "2027-03" });
    expect(rangeAfter(range, "to", "2026-10")).toEqual({ from: "2026-10", to: "2026-10" });
  });

  it("brings the other end along when the first one is moved past it", () => {
    expect(rangeAfter(range, "from", "2027-02")).toEqual({ from: "2027-02", to: "2027-02" });
    expect(rangeAfter(range, "to", "2026-06")).toEqual({ from: "2026-06", to: "2026-06" });
  });

  it("keeps the range within the most months by moving the other end", () => {
    const longest = rangeAfter(range, "to", "2029-01");
    expect(longest).toEqual({ from: "2027-02", to: "2029-01" });
    expect(monthsIn(longest)).toBe(MAX_RANGE_MONTHS);
    const earlier = rangeAfter({ from: "2026-10", to: "2028-09" }, "from", "2025-01");
    expect(earlier).toEqual({ from: "2025-01", to: "2026-12" });
    expect(monthsIn(earlier)).toBe(MAX_RANGE_MONTHS);
  });
});
