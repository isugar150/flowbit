import Link from "next/link";
import { messages } from "@/messages";
import { formatMoment } from "../format";
import { AUDIT_PAGE_SIZE, auditAddress, auditPageCount, type AuditQuery } from "../list-query";
import type { AuditLogEntry } from "../types";

const m = messages.audit;

function detailText(detail: Record<string, string>): string {
  return Object.entries(detail)
    .map(([key, value]) => `${m.detailKeys[key] ?? key}: ${value}`)
    .join(" · ");
}

/** The entries of one page, newest first. */
export function AuditTable({ items, timeZone }: { items: AuditLogEntry[]; timeZone: string }) {
  return (
    <div className="overflow-x-auto rounded-lg border">
      <table className="w-full min-w-[40rem] text-left text-[0.8125rem] leading-5">
        <thead className="border-b bg-muted/40 text-muted-foreground">
          <tr>
            <th scope="col" className="px-3 py-2 font-medium whitespace-nowrap">
              {m.columns.time}
            </th>
            <th scope="col" className="px-3 py-2 font-medium">
              {m.columns.event}
            </th>
            <th scope="col" className="px-3 py-2 font-medium">
              {m.columns.actor}
            </th>
            <th scope="col" className="px-3 py-2 font-medium">
              {m.columns.ip}
            </th>
          </tr>
        </thead>
        <tbody>
          {items.map((entry) => {
            const detail = detailText(entry.detail);
            return (
              <tr key={entry.id} className="border-b align-top last:border-b-0">
                <td className="px-3 py-2 whitespace-nowrap text-muted-foreground tabular-nums">{formatMoment(entry.occurredAt, timeZone)}</td>
                <td className="px-3 py-2">
                  <div className="font-medium">{m.types[entry.type] ?? entry.type}</div>
                  {detail ? <div className="break-all text-muted-foreground">{detail}</div> : null}
                </td>
                <td className="px-3 py-2">
                  {entry.actor ? (entry.actor.displayName ? `${entry.actor.displayName} (${entry.actor.loginId})` : entry.actor.loginId) : m.noActor}
                </td>
                <td className="px-3 py-2 whitespace-nowrap text-muted-foreground" title={entry.userAgent ?? undefined}>
                  {entry.ipAddress ?? "-"}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

/** Which entries the page shows, and the way to the pages before and after it. */
export function AuditPager({ query, total }: { query: AuditQuery; total: number }) {
  if (total === 0) return null;
  const pages = auditPageCount(total);
  const from = (query.page - 1) * AUDIT_PAGE_SIZE + 1;
  const to = Math.min(total, query.page * AUDIT_PAGE_SIZE);
  const link =
    "flex h-8 items-center rounded-md border px-3 text-[0.8125rem] leading-5 outline-none hover:bg-muted focus-visible:ring-3 focus-visible:ring-ring/50 max-md:h-11";
  return (
    <nav aria-label={m.pagesLabel} className="flex items-center justify-between gap-2">
      <span className="text-[0.8125rem] leading-5 text-muted-foreground tabular-nums">{m.range(from, to, total)}</span>
      <span className="flex items-center gap-1.5">
        {query.page > 1 ? (
          <Link href={auditAddress(query, { page: query.page - 1 })} prefetch={false} rel="prev" className={link}>
            {m.previous}
          </Link>
        ) : null}
        {query.page < pages ? (
          <Link href={auditAddress(query, { page: query.page + 1 })} prefetch={false} rel="next" className={link}>
            {m.next}
          </Link>
        ) : null}
      </span>
    </nav>
  );
}
