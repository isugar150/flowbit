/** How many entries a page of the audit log holds. */
export const AUDIT_PAGE_SIZE = 50;

/** What of the log is shown: asked for in the address, so a page can be reloaded, sent and returned to. */
export interface AuditQuery {
  type: string;
  actor: string;
  from: string;
  to: string;
  page: number;
}

type Param = string | string[] | undefined;

const first = (value: Param): string => (Array.isArray(value) ? (value[0] ?? "") : (value ?? ""));

const DATE = /^\d{4}-\d{2}-\d{2}$/;

/** The query an address asks for. What is not understood is left out: a wrong address shows the whole log. */
export function parseAuditQuery(params: { type?: Param; actor?: Param; from?: Param; to?: Param; page?: Param }): AuditQuery {
  const page = Number(first(params.page));
  const from = first(params.from);
  const to = first(params.to);
  return {
    type: /^[A-Z_]+$/.test(first(params.type)) ? first(params.type) : "",
    actor: first(params.actor).trim().slice(0, 64),
    from: DATE.test(from) ? from : "",
    to: DATE.test(to) ? to : "",
    page: Number.isInteger(page) && page >= 1 ? page : 1,
  };
}

/** The address of the log (with `change` made to `query`); a change of the filter starts from the first page. */
export function auditAddress(query: AuditQuery, change: Partial<AuditQuery> = {}): string {
  const next = { ...query, ...change };
  const page = "page" in change ? next.page : Object.keys(change).length > 0 ? 1 : next.page;
  const search = new URLSearchParams();
  if (next.type) search.set("type", next.type);
  if (next.actor) search.set("actor", next.actor);
  if (next.from) search.set("from", next.from);
  if (next.to) search.set("to", next.to);
  if (page > 1) search.set("page", String(page));
  const text = search.toString();
  return text ? `/admin/audit?${text}` : "/admin/audit";
}

/** What to ask the server for the page of the log. */
export function auditApiQuery(query: AuditQuery): string {
  const search = new URLSearchParams({ limit: String(AUDIT_PAGE_SIZE), offset: String((query.page - 1) * AUDIT_PAGE_SIZE) });
  if (query.type) search.set("type", query.type);
  if (query.actor) search.set("actor", query.actor);
  if (query.from) search.set("from", query.from);
  if (query.to) search.set("to", query.to);
  return `?${search}`;
}

export function auditPageCount(total: number): number {
  return Math.max(1, Math.ceil(total / AUDIT_PAGE_SIZE));
}
