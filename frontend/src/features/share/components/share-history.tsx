"use client";

import { useQuery } from "@tanstack/react-query";
import { TimeAgo } from "@/components/dates";
import { Spinner } from "@/components/ui/spinner";
import { FormAlert } from "@/features/auth/components/form-alert";
import { apiErrorMessage } from "@/lib/api/error-message";
import { messages } from "@/messages";
import { fetchShareAccesses } from "../api";
import { describeClient } from "../links";
import type { ShareAccess } from "../types";

const m = messages.share;

function hostOf(address: string | null): string | null {
  if (!address) return null;
  try {
    return new URL(address).host;
  } catch {
    return address;
  }
}

function clientLabel(access: ShareAccess): string {
  const { browser, os } = describeClient(access.userAgent);
  return [browser, os].filter(Boolean).join(" · ") || m.unknownClient;
}

/**
 * When a link was opened and from where, newest first (the person's own link only): the browser and system, the IP address,
 * the page it was shown in when it was in a frame, and what was opened of a link with more than one page. `limit` entries at most
 * (the share window shows the last 20, the person's settings the last 100).
 */
export function ShareHistory({ id, limit, tall }: { id: string; limit?: number; tall?: boolean }) {
  const history = useQuery({ queryKey: ["share-accesses", id, limit ?? null], queryFn: () => fetchShareAccesses(id, limit), staleTime: 0 });
  const items = history.data?.items ?? [];
  return (
    <section aria-label={m.historyTitle} className="flex flex-col gap-1.5">
      <h3 className="flex items-baseline justify-between gap-2 text-[0.8125rem] leading-5 font-medium">
        {m.historyTitle}
        {history.data ? <span className="text-xs leading-4 font-normal text-muted-foreground tabular-nums">{m.historyCount(history.data.total, items.length)}</span> : null}
      </h3>
      {history.isPending ? <Spinner aria-hidden="true" /> : null}
      {history.isError ? <FormAlert>{apiErrorMessage(history.error)}</FormAlert> : null}
      {history.data && items.length === 0 ? <p className="text-[0.8125rem] leading-5 text-muted-foreground">{m.historyEmpty}</p> : null}
      {items.length > 0 ? (
        <ul className={`${tall ? "max-h-80" : "max-h-48"} divide-y overflow-y-auto rounded-lg border px-3`}>
          {items.map((access, index) => {
            const page = access.embedded ? hostOf(access.referrer) : null;
            return (
              <li key={`${access.accessedAt}-${index}`} className="flex flex-col gap-0.5 py-1.5 text-[0.8125rem] leading-5">
                <span className="flex items-baseline justify-between gap-2">
                  <span className="truncate" title={access.userAgent ?? undefined}>
                    {clientLabel(access)}
                    {access.item ? <span className="text-muted-foreground"> · {m.historyItem(access.item)}</span> : null}
                  </span>
                  <span className="shrink-0 text-xs leading-4 text-muted-foreground">
                    <TimeAgo iso={access.accessedAt} />
                  </span>
                </span>
                <span className="truncate text-xs leading-4 text-muted-foreground tabular-nums">
                  {[access.ipAddress, access.embedded ? (page ? m.embeddedIn(page) : m.embedded) : null].filter(Boolean).join(" · ")}
                </span>
              </li>
            );
          })}
        </ul>
      ) : null}
    </section>
  );
}
