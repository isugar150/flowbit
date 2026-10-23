import type { ShareKind } from "./types";

/** The kinds a person's list of their links can be narrowed to, in the order their filter shows them. */
export const SHARE_KINDS: ShareKind[] = ["MY_ISSUES_CALENDAR", "MY_ISSUES_TIMELINE", "ISSUE"];

/** How the list is put in order: the newest first, opened most recently first, opened most often first. */
export const SHARE_SORTS = ["created", "accessed", "opened"] as const;
export type ShareSort = (typeof SHARE_SORTS)[number];

/** How many links a page of the list holds. */
export const SHARES_PAGE_SIZE = 20;

/** Where the list is, and what of it: asked for in the address, so a page can be reloaded, sent and returned to (D-204). */
export type ShareListQuery = { kind: ShareKind | null; sort: ShareSort; page: number };

const DEFAULT: ShareListQuery = { kind: null, sort: "created", page: 1 };

type Param = string | string[] | undefined;

const first = (value: Param): string | undefined => (Array.isArray(value) ? value[0] : value);

/** The list asked for by an address's query. What is not known is the default: a wrong address shows the first page. */
export function parseShareListQuery(params: { kind?: Param; sort?: Param; page?: Param }): ShareListQuery {
  const kind = SHARE_KINDS.find((known) => known === first(params.kind)) ?? null;
  const sort = SHARE_SORTS.find((known) => known === first(params.sort)) ?? DEFAULT.sort;
  const page = Number(first(params.page));
  return { kind, sort, page: Number.isInteger(page) && page >= 1 ? page : DEFAULT.page };
}

/** The address of the list (with `change` made to `query`): only what is not the default is in it, and a change of the filter or the order starts from the first page. */
export function sharesAddress(query: ShareListQuery, change: Partial<ShareListQuery> = {}): string {
  const next = { ...query, ...change };
  const pageGiven = "page" in change;
  const page = pageGiven ? next.page : "kind" in change || "sort" in change ? 1 : next.page;
  const search = new URLSearchParams();
  if (next.kind) search.set("kind", next.kind);
  if (next.sort !== DEFAULT.sort) search.set("sort", next.sort);
  if (page > 1) search.set("page", String(page));
  const text = search.toString();
  return text ? `/settings/shares?${text}` : "/settings/shares";
}

/** What to ask the server for the page of the list. */
export function sharesApiQuery(query: ShareListQuery): string {
  const search = new URLSearchParams({ sort: query.sort, limit: String(SHARES_PAGE_SIZE), offset: String((query.page - 1) * SHARES_PAGE_SIZE) });
  if (query.kind) search.set("kind", query.kind);
  return `?${search}`;
}

/** How many pages `total` links make; one even when there are none. */
export function pageCount(total: number, size = SHARES_PAGE_SIZE): number {
  return Math.max(1, Math.ceil(total / size));
}

/** Which links of the list a page shows, counted from 1: 21 to 40 of 57. */
export function pageRange(page: number, total: number, size = SHARES_PAGE_SIZE): { from: number; to: number } {
  return { from: total === 0 ? 0 : (page - 1) * size + 1, to: Math.min(total, page * size) };
}
