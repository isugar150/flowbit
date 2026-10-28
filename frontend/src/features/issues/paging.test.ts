import { describe, expect, it } from "vitest";
import { ISSUE_MAX_PAGES, ISSUE_PAGE_SIZE, pageLimit, parsePages, splitPage } from "./paging";

describe("parsePages", () => {
  it("reads the number of pages an address asks for", () => {
    expect(parsePages("3")).toBe(3);
    expect(parsePages(["2", "5"])).toBe(2);
  });

  it("is one page when the address says nothing sensible, and stops at the most", () => {
    expect(parsePages(undefined)).toBe(1);
    expect(parsePages("0")).toBe(1);
    expect(parsePages("abc")).toBe(1);
    expect(parsePages("2.5")).toBe(1);
    expect(parsePages("999")).toBe(ISSUE_MAX_PAGES);
  });
});

describe("pageLimit and splitPage", () => {
  it("asks the server for one more than is shown", () => {
    expect(pageLimit(1)).toBe(ISSUE_PAGE_SIZE + 1);
    expect(pageLimit(2)).toBe(2 * ISSUE_PAGE_SIZE + 1);
  });

  it("knows there is more when the extra one came", () => {
    const list = Array.from({ length: ISSUE_PAGE_SIZE + 1 }, (_, index) => index);
    const page = splitPage(list, 1);
    expect(page.items).toHaveLength(ISSUE_PAGE_SIZE);
    expect(page.hasMore).toBe(true);
  });

  it("has no more when the server sent no more than the pages", () => {
    const page = splitPage([1, 2, 3], 1);
    expect(page.items).toEqual([1, 2, 3]);
    expect(page.hasMore).toBe(false);
    expect(splitPage(null, 1)).toEqual({ items: [], hasMore: false });
  });
});
