import Link from "next/link";
import { cn } from "@/lib/utils";
import { messages } from "@/messages";
import { SHARE_KINDS, SHARE_SORTS, pageCount, pageRange, sharesAddress, type ShareListQuery, SHARES_PAGE_SIZE } from "../list-query";
import type { ShareKind } from "../types";

const m = messages.share;

/** A choice of the list shown as a link: the one in force is marked. The address holds the choice, so it needs no script. */
function Choice({ href, current, children }: { href: string; current: boolean; children: React.ReactNode }) {
  return (
    <Link
      href={href}
      prefetch={false}
      aria-current={current ? "true" : undefined}
      className={cn(
        "flex h-7 shrink-0 items-center rounded-full border px-3 text-[0.8125rem] leading-5 whitespace-nowrap outline-none focus-visible:ring-3 focus-visible:ring-ring/50 max-md:h-9",
        current ? "border-transparent bg-secondary font-medium text-foreground" : "text-muted-foreground hover:bg-muted hover:text-foreground",
      )}
    >
      {children}
    </Link>
  );
}

/**
 * What narrows and orders the list of the person's links (D-204): the kind (with how many there are of each) and the order.
 * Each is a link to the same list with that choice made, from its first page.
 */
export function ShareListToolbar({ query, kinds }: { query: ShareListQuery; kinds: Partial<Record<ShareKind, number>> }) {
  const all = SHARE_KINDS.reduce((sum, kind) => sum + (kinds[kind] ?? 0), 0);
  return (
    <div className="flex flex-col gap-2">
      <nav aria-label={m.mine.kindFilter} className="flex flex-wrap items-center gap-1.5">
        <Choice href={sharesAddress(query, { kind: null })} current={query.kind === null}>
          {m.mine.all} <span className="ml-1 tabular-nums">{all}</span>
        </Choice>
        {SHARE_KINDS.map((kind) => (
          <Choice key={kind} href={sharesAddress(query, { kind })} current={query.kind === kind}>
            {m.kinds[kind].label} <span className="ml-1 tabular-nums">{kinds[kind] ?? 0}</span>
          </Choice>
        ))}
      </nav>
      <nav aria-label={m.mine.sortLabel} className="flex flex-wrap items-center gap-1.5">
        <span className="mr-1 text-xs leading-4 text-muted-foreground">{m.mine.sortLabel}</span>
        {SHARE_SORTS.map((sort) => (
          <Choice key={sort} href={sharesAddress(query, { sort })} current={query.sort === sort}>
            {m.mine.sorts[sort]}
          </Choice>
        ))}
      </nav>
    </div>
  );
}

/** Which links of the list the page shows, and the way to the pages before and after it. */
export function ShareListPager({ query, total }: { query: ShareListQuery; total: number }) {
  const pages = pageCount(total, SHARES_PAGE_SIZE);
  if (total === 0) return null;
  const { from, to } = pageRange(query.page, total, SHARES_PAGE_SIZE);
  const link = "flex h-8 items-center rounded-md border px-3 text-[0.8125rem] leading-5 outline-none hover:bg-muted focus-visible:ring-3 focus-visible:ring-ring/50 max-md:h-11";
  return (
    <nav aria-label={m.mine.pagesLabel} className="flex items-center justify-between gap-2">
      <span className="text-[0.8125rem] leading-5 text-muted-foreground tabular-nums">{m.mine.range(from, to, total)}</span>
      <span className="flex items-center gap-1.5">
        {query.page > 1 ? (
          <Link href={sharesAddress(query, { page: query.page - 1 })} prefetch={false} rel="prev" className={link}>
            {m.mine.previous}
          </Link>
        ) : null}
        {query.page < pages ? (
          <Link href={sharesAddress(query, { page: query.page + 1 })} prefetch={false} rel="next" className={link}>
            {m.mine.next}
          </Link>
        ) : null}
      </span>
    </nav>
  );
}
