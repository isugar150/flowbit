/**
 * The lists of issues are loaded a thousand at a time (D-227). How many thousands the person has asked for is `?pages=` in the
 * address, so a reload, the back button and a shared link keep it; the server reads it again each time, so every issue shown is as
 * fresh as the page itself. The server is asked for one more than is shown, to know whether there is more.
 */
export const ISSUE_PAGE_SIZE = 1000;

/** The most a list can hold: ten thousand (the server stops there too). */
export const ISSUE_MAX_PAGES = 10;

export const PAGES_KEY = "pages";

type Param = string | string[] | null | undefined;

/** How many pages an address asks for; one when it says nothing sensible. */
export function parsePages(value: Param): number {
  const text = Array.isArray(value) ? value[0] : value;
  const pages = Number(text);
  return Number.isInteger(pages) && pages >= 1 ? Math.min(pages, ISSUE_MAX_PAGES) : 1;
}

/** What to ask the server so that `pages` pages and one more are sent. */
export function pageLimit(pages: number): number {
  return Math.min(pages, ISSUE_MAX_PAGES) * ISSUE_PAGE_SIZE + 1;
}

/** The pages asked for out of what the server sent, and whether there was one more than that. */
export function splitPage<T>(list: T[] | null, pages: number): { items: T[]; hasMore: boolean } {
  const size = Math.min(pages, ISSUE_MAX_PAGES) * ISSUE_PAGE_SIZE;
  const all = list ?? [];
  return { items: all.slice(0, size), hasMore: all.length > size };
}
