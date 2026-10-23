import { describe, expect, it } from "vitest";
import { pageCount, pageRange, parseShareListQuery, sharesAddress, sharesApiQuery } from "./list-query";

describe("parseShareListQuery", () => {
  it("reads the kind, the order and the page from an address's query", () => {
    expect(parseShareListQuery({ kind: "ISSUE", sort: "accessed", page: "3" })).toEqual({ kind: "ISSUE", sort: "accessed", page: 3 });
    expect(parseShareListQuery({ kind: ["MY_ISSUES_TIMELINE", "ISSUE"], sort: "opened" })).toEqual({ kind: "MY_ISSUES_TIMELINE", sort: "opened", page: 1 });
  });

  it("starts from the newest first, all kinds, the first page when the query says nothing", () => {
    expect(parseShareListQuery({})).toEqual({ kind: null, sort: "created", page: 1 });
  });

  it("takes what it does not know as the first page of the default list", () => {
    expect(parseShareListQuery({ kind: "DOCUMENT", sort: "newest", page: "0" })).toEqual({ kind: null, sort: "created", page: 1 });
    expect(parseShareListQuery({ page: "abc" }).page).toBe(1);
    expect(parseShareListQuery({ page: "2.5" }).page).toBe(1);
  });
});

describe("sharesAddress", () => {
  const list = { kind: null, sort: "created", page: 1 } as const;

  it("is the plain address for the default list", () => {
    expect(sharesAddress(list)).toBe("/settings/shares");
  });

  it("holds only what is not the default", () => {
    expect(sharesAddress({ kind: "ISSUE", sort: "accessed", page: 2 })).toBe("/settings/shares?kind=ISSUE&sort=accessed&page=2");
    expect(sharesAddress({ ...list, page: 2 })).toBe("/settings/shares?page=2");
  });

  it("goes to another page of the same list", () => {
    expect(sharesAddress({ kind: "ISSUE", sort: "opened", page: 2 }, { page: 3 })).toBe("/settings/shares?kind=ISSUE&sort=opened&page=3");
    expect(sharesAddress({ kind: "ISSUE", sort: "opened", page: 3 }, { page: 1 })).toBe("/settings/shares?kind=ISSUE&sort=opened");
  });

  it("starts from the first page when the kind or the order is changed", () => {
    expect(sharesAddress({ kind: null, sort: "created", page: 4 }, { kind: "ISSUE" })).toBe("/settings/shares?kind=ISSUE");
    expect(sharesAddress({ kind: "ISSUE", sort: "created", page: 4 }, { sort: "opened" })).toBe("/settings/shares?kind=ISSUE&sort=opened");
    expect(sharesAddress({ kind: "ISSUE", sort: "created", page: 4 }, { kind: null })).toBe("/settings/shares");
  });
});

describe("sharesApiQuery", () => {
  it("asks for one page, from where it starts", () => {
    expect(sharesApiQuery({ kind: null, sort: "created", page: 1 })).toBe("?sort=created&limit=20&offset=0");
    expect(sharesApiQuery({ kind: "ISSUE", sort: "opened", page: 3 })).toBe("?sort=opened&limit=20&offset=40&kind=ISSUE");
  });
});

describe("pages", () => {
  it("counts the pages, one at least", () => {
    expect(pageCount(0)).toBe(1);
    expect(pageCount(20)).toBe(1);
    expect(pageCount(21)).toBe(2);
    expect(pageCount(57)).toBe(3);
  });

  it("says which links of the list a page shows", () => {
    expect(pageRange(1, 57)).toEqual({ from: 1, to: 20 });
    expect(pageRange(3, 57)).toEqual({ from: 41, to: 57 });
    expect(pageRange(1, 0)).toEqual({ from: 0, to: 0 });
  });
});
