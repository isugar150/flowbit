import { describe, expect, it } from "vitest";
import { timeAgo } from "./time-ago";

const NOW = Date.parse("2026-10-26T12:00:00Z");
const before = (seconds: number) => new Date(NOW - seconds * 1000).toISOString();

describe("timeAgo", () => {
  it("says how long ago in the reader's words", () => {
    expect(timeAgo(before(10), NOW)).toBe("지금");
    expect(timeAgo(before(3 * 60), NOW)).toBe("3분 전");
    expect(timeAgo(before(2 * 3_600), NOW)).toBe("2시간 전");
    expect(timeAgo(before(86_400), NOW)).toBe("어제");
    expect(timeAgo(before(14 * 86_400), NOW)).toBe("2주 전");
  });

  it("moves on as the clock does", () => {
    // The same moment reads differently later: this is what the page redraws every few seconds.
    const posted = before(0);
    expect(timeAgo(posted, NOW + 2 * 60_000)).toBe("2분 전");
    expect(timeAgo(posted, NOW + 5 * 60_000)).toBe("5분 전");
    expect(timeAgo(posted, NOW + 3 * 3_600_000)).toBe("3시간 전");
  });
});
