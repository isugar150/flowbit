"use client";

import { useQuery } from "@tanstack/react-query";
import { LinkIcon } from "lucide-react";
import { Wordmark } from "@/components/brand/wordmark";
import { Spinner } from "@/components/ui/spinner";
import { Empty, EmptyDescription, EmptyHeader, EmptyMedia, EmptyTitle } from "@/components/ui/empty";
import { IssueCalendarView } from "@/features/issues/components/issue-calendar";
import { ApiError } from "@/lib/api/errors";
import { messages } from "@/messages";
import { fetchPublicShare } from "../api";
import type { PublicShare } from "../types";

const m = messages.share.page;
const calendarUnder = () => null;

/** Where this page is shown, for the record of who opened the link: inside a frame of another page or not. */
function whereAmI(): { embedded: boolean; referrer: string } {
  try {
    return { embedded: window.self !== window.top, referrer: document.referrer };
  } catch {
    // A page that cannot even be compared with its parent is in a frame.
    return { embedded: true, referrer: document.referrer };
  }
}

/** The month of a shared calendar, from its `month` parameter (2026-10). */
function monthOf(params: Record<string, string>): { year: number; month: number } | null {
  const found = /^(\d{4})-(\d{2})$/.exec(params.month ?? "");
  return found ? { year: Number(found[1]), month: Number(found[2]) - 1 } : null;
}

function SharedCalendar({ share }: { share: PublicShare }) {
  const month = monthOf(share.params);
  const scope = share.params.scope === "created" ? m.scope.created : m.scope.assigned;
  return (
    <>
      <header className="flex flex-col gap-0.5 px-4 pt-4">
        <h1 className="text-lg leading-7 font-semibold">{m.titleBy(share.sharedBy)}</h1>
        <p className="text-[0.8125rem] leading-5 text-muted-foreground">{m.source(scope)}</p>
      </header>
      {month ? (
        <IssueCalendarView issues={share.content.issues} fixedMonth={month} issueHref={calendarUnder} />
      ) : null}
      {month && share.content.issues.length === 0 ? <p className="px-4 pb-6 text-[0.8125rem] leading-5 text-muted-foreground">{m.empty}</p> : null}
    </>
  );
}

/**
 * The page of a share link (docs/features/sharing.md): the shared content for anyone who has the address, without
 * signing in. Opening it is what the link's record of visits counts, so it asks once and not again when the window
 * is focused or the connection comes back.
 */
export function SharedView({ token }: { token: string }) {
  const share = useQuery({
    queryKey: ["shared", token],
    queryFn: () => fetchPublicShare(token, whereAmI()),
    retry: false,
    staleTime: Infinity,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
  });
  const notFound = share.error instanceof ApiError && share.error.status === 404;

  return (
    <div className="mx-auto flex min-h-dvh w-full max-w-6xl flex-col">
      <main className="flex flex-1 flex-col gap-2 pb-4">
        {share.isPending ? (
          <p role="status" className="flex items-center gap-2 px-4 pt-6 text-[0.8125rem] leading-5 text-muted-foreground">
            <Spinner aria-hidden="true" />
            {m.loading}
          </p>
        ) : null}
        {share.isError ? (
          <Empty className="pt-16">
            <EmptyHeader>
              <EmptyMedia variant="icon">
                <LinkIcon />
              </EmptyMedia>
              <EmptyTitle>
                <h1>{notFound ? m.notFoundTitle : m.failed}</h1>
              </EmptyTitle>
              {notFound ? <EmptyDescription>{m.notFoundHint}</EmptyDescription> : null}
            </EmptyHeader>
          </Empty>
        ) : null}
        {share.data?.kind === "MY_ISSUES_CALENDAR" ? <SharedCalendar share={share.data} /> : null}
      </main>
      <footer className="flex items-center gap-2 px-4 pb-4 text-xs leading-4 text-muted-foreground">
        <Wordmark className="text-sm [&_svg]:size-4" />
        <span>{m.sharedOn}</span>
      </footer>
    </div>
  );
}
