"use client";

import { useCallback, useState } from "react";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { CalendarIcon, ChartGanttIcon, Columns3Icon, ListIcon } from "lucide-react";
import { cn } from "@/lib/utils";
import { messages } from "@/messages";

const m = messages.issues.views;

export const ISSUE_VIEWS = ["list", "board", "timeline", "calendar"] as const;
export type IssueViewMode = (typeof ISSUE_VIEWS)[number];

/**
 * The address of the list itself. While an issue is open in a dialog over the list the browser's
 * address is the issue's (`/issue/FB-12`), without the list's view and filters; the list then
 * keeps the address it had, so a calendar stays a calendar behind the dialog. A list drawn behind
 * an issue that was opened by its address is the list of all issues.
 */
export function useListAddress(): { pathname: string; searchParams: URLSearchParams } {
  const pathname = usePathname();
  const searchParams = useSearchParams();
  const query = searchParams.toString();
  const behindIssue = pathname.startsWith("/issue/");
  const [kept, setKept] = useState({ pathname: behindIssue ? "/issues" : pathname, query: behindIssue ? "" : query });
  if (!behindIssue && (kept.pathname !== pathname || kept.query !== query)) setKept({ pathname, query });
  return behindIssue
    ? { pathname: kept.pathname, searchParams: new URLSearchParams(kept.query) }
    : { pathname, searchParams: new URLSearchParams(query) };
}

/**
 * Changes the list's query (its view and filters) in the address. The address is changed at once,
 * so the list redraws without waiting for anything; then the router is told the same address.
 *
 * The second step is not optional. An address changed only through `history` leaves the router
 * believing the list is still at its old address. Nothing shows that until an issue is opened in
 * a dialog over the list and something is saved: the refresh that follows (router.refresh) then
 * gets an answer for a page the router does not think it is on, and Next.js falls back to
 * loading the whole document again. The screen blinks, and the list behind the dialog comes
 * back as the plain list of all issues, its view and filters gone.
 */
export function useReplaceListQuery(): (pathname: string, params: URLSearchParams) => void {
  const router = useRouter();
  return useCallback(
    (pathname, params) => {
      const query = params.toString();
      const address = query ? `${pathname}?${query}` : pathname;
      window.history.replaceState(null, "", address);
      router.replace(address, { scroll: false });
    },
    [router],
  );
}

/**
 * Which way a list of issues is shown: as the list, as a timeline chart by project
 * (issue-timeline.tsx), or on a calendar (issue-calendar.tsx). Kept
 * in the address (`?view=timeline`), so a reload, the back button and a shared link keep it.
 */
export function useIssueView(): [IssueViewMode, (view: IssueViewMode) => void] {
  const { pathname, searchParams } = useListAddress();
  const replaceListQuery = useReplaceListQuery();
  const param = searchParams.get("view");
  const view = ISSUE_VIEWS.find((item) => item === param) ?? "list";
  const setView = (next: IssueViewMode) => {
    const params = new URLSearchParams(searchParams.toString());
    if (next === "list") params.delete("view");
    else params.set("view", next);
    replaceListQuery(pathname, params);
  };
  return [view, setView];
}

const VIEW_ICONS = { list: ListIcon, board: Columns3Icon, timeline: ChartGanttIcon, calendar: CalendarIcon };

/** The three buttons that switch between the views. */
export function IssueViewSwitch({ view, onChange }: { view: IssueViewMode; onChange: (view: IssueViewMode) => void }) {
  return (
    <div role="group" aria-label={m.label} className="flex shrink-0 items-center rounded-full border p-0.5">
      {ISSUE_VIEWS.map((item) => {
        const Icon = VIEW_ICONS[item];
        return (
          <button
            key={item}
            type="button"
            aria-pressed={view === item}
            title={m[item]}
            onClick={() => onChange(item)}
            className={cn(
              "flex h-6 shrink-0 items-center gap-1.5 rounded-full px-2.5 text-[0.8125rem] whitespace-nowrap leading-5 outline-none focus-visible:ring-3 focus-visible:ring-ring/50 max-md:h-8",
              view === item ? "bg-secondary font-medium text-foreground" : "text-muted-foreground hover:text-foreground",
            )}
          >
            <Icon className="size-3.5" aria-hidden="true" />
            <span className="max-sm:sr-only">{m[item]}</span>
          </button>
        );
      })}
    </div>
  );
}
