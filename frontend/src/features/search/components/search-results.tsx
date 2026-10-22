"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { SearchIcon } from "lucide-react";
import { Spinner } from "@/components/ui/spinner";
import { cn } from "@/lib/utils";
import { messages } from "@/messages";
import { SEARCH_MAX_LENGTH, search } from "../api";
import { wordsOf } from "../highlight";
import {
  DocumentHitContent,
  IssueHitContent,
  ProjectHitContent,
  documentHref,
  issueHref,
  projectHref,
} from "./hit-content";
import { useDebounced } from "./search-dialog";

const m = messages.search;

export type SearchTab = "all" | "issues" | "documents" | "projects";

const PAGE_LIMIT = 50;

const rowClass =
  "flex items-start gap-2 rounded-md px-3 py-2.5 outline-none hover:bg-accent focus-visible:ring-3 focus-visible:ring-ring/50";

/** The search page: one input, tabs by kind, and up to 50 results of each. The address follows the input. */
export function SearchResults({ initialQuery, initialTab }: { initialQuery: string; initialTab: SearchTab }) {
  const pathname = usePathname();
  const [query, setQuery] = useState(initialQuery);
  const [tab, setTab] = useState<SearchTab>(initialTab);
  const debounced = useDebounced(query.trim(), 200);
  const result = useQuery({
    queryKey: ["search", debounced, PAGE_LIMIT],
    queryFn: () => search(debounced, PAGE_LIMIT),
    enabled: debounced.length > 0,
    placeholderData: keepPreviousData,
  });
  const data = debounced ? result.data : undefined;
  const words = wordsOf(debounced);

  // Keep the address in step, so the page can be shared and comes back on reload.
  useEffect(() => {
    const params = new URLSearchParams();
    if (debounced) params.set("q", debounced);
    if (tab !== "all") params.set("type", tab);
    const search = params.toString();
    window.history.replaceState(null, "", search ? `${pathname}?${search}` : pathname);
  }, [debounced, tab, pathname]);

  const count = (n: number, more: boolean) => (more ? `${n}+` : String(n));
  const tabs: { id: SearchTab; label: string; count?: string }[] = [
    { id: "all", label: m.all },
    { id: "issues", label: m.issues, count: data ? count(data.issues.length, data.issuesMore) : undefined },
    { id: "documents", label: m.documents, count: data ? count(data.documents.length, data.documentsMore) : undefined },
    { id: "projects", label: m.projects, count: data ? count(data.projects.length, data.projectsMore) : undefined },
  ];
  const shows = (kind: SearchTab) => tab === "all" || tab === kind;
  const issues = data && shows("issues") ? data.issues : [];
  const documents = data && shows("documents") ? data.documents : [];
  const projects = data && shows("projects") ? data.projects : [];
  const more = data
    ? (shows("issues") && data.issuesMore) ||
      (shows("documents") && data.documentsMore) ||
      (shows("projects") && data.projectsMore)
    : false;

  return (
    <div className="mx-auto flex w-full max-w-3xl flex-col gap-4 px-4 py-6 sm:px-6">
      <div className="flex h-11 items-center gap-2.5 rounded-lg border bg-background px-3 focus-within:border-ring focus-within:ring-3 focus-within:ring-ring/50">
        <SearchIcon className="size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
        <input
          autoFocus
          type="search"
          aria-label={m.inputLabel}
          placeholder={m.placeholder}
          maxLength={SEARCH_MAX_LENGTH}
          autoComplete="off"
          spellCheck={false}
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          className="h-full min-w-0 flex-1 bg-transparent text-[0.9375rem] outline-none placeholder:text-muted-foreground"
        />
        {result.isFetching ? <Spinner className="size-4 text-muted-foreground" aria-hidden="true" /> : null}
      </div>

      <div role="tablist" aria-label={m.kindsLabel} className="flex gap-1 border-b">
        {tabs.map((item) => (
          <button
            key={item.id}
            type="button"
            role="tab"
            aria-selected={tab === item.id}
            onClick={() => setTab(item.id)}
            className={cn(
              "-mb-px flex h-9 items-center gap-1.5 border-b-2 px-2.5 text-[0.8125rem] font-medium outline-none focus-visible:ring-3 focus-visible:ring-ring/50 max-md:h-11",
              tab === item.id ? "border-foreground text-foreground" : "border-transparent text-muted-foreground hover:text-foreground",
            )}
          >
            {item.label}
            {item.count !== undefined ? <span className="text-xs text-muted-foreground tabular-nums">{item.count}</span> : null}
          </button>
        ))}
      </div>

      {!debounced ? (
        <p className="py-10 text-center text-[0.8125rem] leading-5 text-muted-foreground">{m.emptyHint}</p>
      ) : data && issues.length + documents.length + projects.length === 0 ? (
        <div className="flex flex-col items-center gap-1.5 py-10 text-center">
          <p className="text-[0.875rem] font-medium">{m.noResults(debounced)}</p>
          <p className="text-[0.8125rem] text-muted-foreground">{m.noResultsHint}</p>
        </div>
      ) : (
        <div className="flex flex-col gap-6">
          {issues.length > 0 ? (
            <section aria-label={m.issues} className="flex flex-col">
              {tab === "all" ? <h2 className="px-3 pb-1.5 text-xs font-medium text-muted-foreground">{m.issues}</h2> : null}
              <ul className="flex flex-col">
                {issues.map((hit) => (
                  <li key={hit.id}>
                    <Link href={issueHref(hit)} className={rowClass}>
                      <IssueHitContent hit={hit} words={words} />
                    </Link>
                  </li>
                ))}
              </ul>
            </section>
          ) : null}
          {documents.length > 0 ? (
            <section aria-label={m.documents} className="flex flex-col">
              {tab === "all" ? <h2 className="px-3 pb-1.5 text-xs font-medium text-muted-foreground">{m.documents}</h2> : null}
              <ul className="flex flex-col">
                {documents.map((hit) => (
                  <li key={hit.id}>
                    <Link href={documentHref(hit)} className={rowClass}>
                      <DocumentHitContent hit={hit} words={words} />
                    </Link>
                  </li>
                ))}
              </ul>
            </section>
          ) : null}
          {projects.length > 0 ? (
            <section aria-label={m.projects} className="flex flex-col">
              {tab === "all" ? <h2 className="px-3 pb-1.5 text-xs font-medium text-muted-foreground">{m.projects}</h2> : null}
              <ul className="flex flex-col">
                {projects.map((hit) => (
                  <li key={hit.id}>
                    <Link href={projectHref(hit)} className={rowClass}>
                      <ProjectHitContent hit={hit} words={words} />
                    </Link>
                  </li>
                ))}
              </ul>
            </section>
          ) : null}
          {more ? <p className="px-3 text-[0.8125rem] text-muted-foreground">{m.tooMany(PAGE_LIMIT)}</p> : null}
        </div>
      )}
    </div>
  );
}
