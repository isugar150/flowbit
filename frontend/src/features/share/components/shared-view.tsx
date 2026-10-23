"use client";

import dynamic from "next/dynamic";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import { LinkIcon } from "lucide-react";
import { Wordmark } from "@/components/brand/wordmark";
import { Spinner } from "@/components/ui/spinner";
import { buttonVariants } from "@/components/ui/button";
import { Empty, EmptyContent, EmptyDescription, EmptyHeader, EmptyMedia, EmptyTitle } from "@/components/ui/empty";
import { IssueCalendarView } from "@/features/issues/components/issue-calendar";
import { IssueTimeline } from "@/features/issues/components/issue-timeline";
import { ApiError } from "@/lib/api/errors";
import { messages } from "@/messages";
import { fetchPublicItem, fetchPublicShare } from "../api";
import { sharedCardOf } from "../shared-card";
import type { PublicShare } from "../types";

// A shared issue reads its text in the editor, which is large: only a link to an issue loads it.
const SharedIssuePage = dynamic(() => import("./shared-issue").then((module) => module.SharedIssuePage));
const SharedDocumentPage = dynamic(() => import("./shared-issue").then((module) => module.SharedDocumentPage));

const m = messages.share.page;
/** An issue on the shared page opens nothing: what it would open needs a sign-in. */
const opensNothing = () => null;

/** Where this page is shown, for the record of who opened the link: inside a frame of another page or not. */
function whereAmI(): { embedded: boolean; referrer: string } {
  try {
    return { embedded: window.self !== window.top, referrer: document.referrer };
  } catch {
    // A page that cannot even be compared with its parent is in a frame.
    return { embedded: true, referrer: document.referrer };
  }
}

const MONTH = /^\d{4}-\d{2}$/;

/** The month of a shared calendar, from its `month` parameter (2026-10). */
function monthOf(params: Record<string, string>): { year: number; month: number } | null {
  const found = /^(\d{4})-(\d{2})$/.exec(params.month ?? "");
  return found ? { year: Number(found[1]), month: Number(found[2]) - 1 } : null;
}

/** The months of a shared timeline, from its `from` and `to` parameters (2026-10). */
function rangeOf(params: Record<string, string>): { from: string; to: string } | null {
  const { from, to } = params;
  return MONTH.test(from ?? "") && MONTH.test(to ?? "") ? { from, to } : null;
}

const monthLabel = (month: string) => messages.issues.views.monthTitle(Number(month.slice(0, 4)), Number(month.slice(5, 7)));

/** Who shared what: the title of the page and, for "내 티켓", where its issues come from. */
function SharedHeader({ share, detail }: { share: PublicShare; detail?: string }) {
  const scope = share.kind === "ISSUE" ? null : share.params.scope === "created" ? m.scope.created : m.scope.assigned;
  const line = [scope ? m.source(scope) : null, detail].filter(Boolean).join(" · ");
  return (
    <header className="flex flex-col gap-0.5 px-4 pt-4">
      <h1 className="text-lg leading-7 font-semibold">{m.titleBy(share.sharedBy, messages.share.kinds[share.kind].label)}</h1>
      {line ? <p className="text-[0.8125rem] leading-5 text-muted-foreground tabular-nums">{line}</p> : null}
    </header>
  );
}

/** What a link shows, by its kind. `token` is the link's, for the addresses of the pages under a shared issue. */
function SharedContent({ share, token }: { share: PublicShare; token: string }) {
  if (share.kind === "MY_ISSUES_CALENDAR") {
    const month = monthOf(share.params);
    return (
      <>
        <SharedHeader share={share} />
        {month ? <IssueCalendarView issues={share.content.issues} fixedMonth={month} issueHref={opensNothing} cardOf={sharedCardOf} /> : null}
        {month && share.content.issues.length === 0 ? <p className="px-4 pb-6 text-[0.8125rem] leading-5 text-muted-foreground">{m.empty}</p> : null}
      </>
    );
  }
  if (share.kind === "ISSUE") {
    // The page of an issue or document has its own title and path; this one only says who shared it.
    const page = share.content;
    return (
      <>
        <SharedHeader share={share} />
        {page.type === "issue" ? <SharedIssuePage token={token} issue={page} /> : <SharedDocumentPage token={token} document={page} />}
      </>
    );
  }
  const range = rangeOf(share.params);
  // The issues come without their parents: each is a line of its own. A project is told by its name only, which is also what groups them.
  const issues = share.content.issues.map((issue) => ({
    ...issue,
    project: issue.project ? { id: issue.project, name: issue.project } : null,
    ancestors: [],
  }));
  return (
    <>
      <SharedHeader share={share} detail={range ? messages.share.range.summary(monthLabel(range.from), monthLabel(range.to)) : undefined} />
      {range ? <IssueTimeline issues={issues} range={range} issueHref={opensNothing} /> : null}
      {range && issues.length === 0 ? <p className="px-4 pb-6 text-[0.8125rem] leading-5 text-muted-foreground">{m.emptyRange}</p> : null}
    </>
  );
}

/**
 * The page of a share link (docs/features/sharing.md): the shared content for anyone who has the address, without
 * signing in. Opening it is what the link's record of visits counts, so it asks once and not again when the window
 * is focused or the connection comes back.
 */
export function SharedView({ token }: { token: string }) {
  // A shared issue leads to its sub-issues and documents inside the same link: `?issue=13`, `?doc={id}`.
  const searchParams = useSearchParams();
  const doc = searchParams.get("doc");
  const issue = searchParams.get("issue");
  const item = doc ? ({ type: "document", id: doc } as const) : issue ? ({ type: "issue", id: issue } as const) : null;
  const share = useQuery({
    queryKey: ["shared", token, item?.type ?? "", item?.id ?? ""],
    queryFn: () => (item ? fetchPublicItem(token, item.type, item.id, whereAmI()) : fetchPublicShare(token, whereAmI())),
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
                <h1>{notFound ? (item ? m.issue.unavailableTitle : m.notFoundTitle) : m.failed}</h1>
              </EmptyTitle>
              {notFound ? <EmptyDescription>{item ? m.issue.unavailableHint : m.notFoundHint}</EmptyDescription> : null}
            </EmptyHeader>
            {/* A page under the link that cannot be opened leads back to the link's own page. */}
            {notFound && item ? (
              <EmptyContent>
                <Link href={`/s/${token}`} prefetch={false} className={buttonVariants({ variant: "outline" })}>
                  {m.issue.backToShared}
                </Link>
              </EmptyContent>
            ) : null}
          </Empty>
        ) : null}
        {share.data ? <SharedContent share={share.data} token={token} /> : null}
      </main>
      <footer className="flex items-center gap-2 px-4 pb-4 text-xs leading-4 text-muted-foreground">
        <Wordmark className="text-sm [&_svg]:size-4" />
        <span>{m.sharedOn}</span>
      </footer>
    </div>
  );
}
