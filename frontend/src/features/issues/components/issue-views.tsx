"use client";

import { useState } from "react";
import { usePathname, useSearchParams } from "next/navigation";
import { CalendarIcon, ChartGanttIcon, ListIcon } from "lucide-react";
import { cn } from "@/lib/utils";
import { messages } from "@/messages";

const m = messages.issues.views;

export const ISSUE_VIEWS = ["list", "milestone", "calendar"] as const;
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

/** Changes the list's query in the address only: the issues are already here, so the server is not asked again. */
export function replaceListQuery(pathname: string, params: URLSearchParams) {
  const query = params.toString();
  window.history.replaceState(null, "", query ? `${pathname}?${query}` : pathname);
}

/**
 * Which way a list of issues is shown: as the list, as a timeline chart by milestone
 * (issue-timeline.tsx), or on a calendar (issue-calendar.tsx). Kept
 * in the address (`?view=milestone`), so a reload, the back button and a shared link keep it.
 */
export function useIssueView(): [IssueViewMode, (view: IssueViewMode) => void] {
  const { pathname, searchParams } = useListAddress();
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

const VIEW_ICONS = { list: ListIcon, milestone: ChartGanttIcon, calendar: CalendarIcon };

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
              "flex h-6 items-center gap-1.5 rounded-full px-2.5 text-[0.8125rem] leading-5 outline-none focus-visible:ring-3 focus-visible:ring-ring/50 max-md:h-8",
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
