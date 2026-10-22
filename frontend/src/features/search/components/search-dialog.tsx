"use client";

import { createContext, useCallback, useContext, useEffect, useId, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { Dialog as DialogPrimitive } from "@base-ui/react/dialog";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { CornerDownLeftIcon, SearchIcon } from "lucide-react";
import { ModKey } from "@/components/mod-key";
import { Button } from "@/components/ui/button";
import { Kbd, KbdGroup } from "@/components/ui/kbd";
import { Spinner } from "@/components/ui/spinner";
import { isTyping } from "@/lib/is-typing";
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

const m = messages.search;

/** Results per kind in the dialog; the search page shows more. */
const DIALOG_LIMIT = 5;
/** How long typing must pause before a search goes out. */
const DEBOUNCE_MS = 150;

export function useDebounced<T>(value: T, delay: number): T {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const timer = setTimeout(() => setDebounced(value), delay);
    return () => clearTimeout(timer);
  }, [value, delay]);
  return debounced;
}

export function searchPageHref(query: string): string {
  return query.trim() ? `/search?q=${encodeURIComponent(query.trim())}` : "/search";
}

const SearchContext = createContext<(() => void) | null>(null);

/**
 * The search dialog, from anywhere: `Ctrl/⌘+K`, `/` when not typing, the sidebar's "검색" and
 * the search button of the narrow top bar (docs/features/search.md).
 */
export function SearchProvider({ children }: { children: React.ReactNode }) {
  const [open, setOpen] = useState(false);
  const show = useCallback(() => setOpen(true), []);

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.isComposing) return;
      // `code`: the physical key, so it works while a Korean input method is on.
      const palette = event.code === "KeyK" && (event.ctrlKey || event.metaKey) && !event.altKey && !event.shiftKey;
      const slash = event.key === "/" && !event.ctrlKey && !event.metaKey && !event.altKey;
      if (palette) {
        event.preventDefault();
        setOpen((current) => !current);
        return;
      }
      if (slash && !isTyping(event.target) && !document.querySelector("[role=dialog]")) {
        event.preventDefault();
        setOpen(true);
      }
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, []);

  return (
    <SearchContext value={show}>
      {children}
      <DialogPrimitive.Root open={open} onOpenChange={setOpen}>
        <DialogPrimitive.Portal>
          {/* No motion on open or close: this is opened from the keyboard many times a day. */}
          <DialogPrimitive.Backdrop className="fixed inset-0 z-50 bg-black/20" />
          <DialogPrimitive.Popup
            aria-label={m.title}
            className="fixed top-[12vh] left-1/2 z-50 flex max-h-[min(36rem,76vh)] w-[min(40rem,calc(100%-1.5rem))] -translate-x-1/2 flex-col overflow-hidden rounded-xl bg-popover text-sm text-popover-foreground shadow-2xl ring-1 ring-foreground/10 outline-none max-sm:top-3 max-sm:max-h-[calc(100dvh-1.5rem)]"
          >
            {open ? <SearchPanel onClose={() => setOpen(false)} /> : null}
          </DialogPrimitive.Popup>
        </DialogPrimitive.Portal>
      </DialogPrimitive.Root>
    </SearchContext>
  );
}

type Item = { id: string; href: string };

/** One kind of result in the list: a heading and its lines. */
type Group = { kind: string; label: string; entries: { id: string; href: string; node: React.ReactNode }[] };

/** The input and the results; mounted only while the dialog is open, so each opening starts empty. */
function SearchPanel({ onClose }: { onClose: () => void }) {
  const router = useRouter();
  const listId = useId();
  const [query, setQuery] = useState("");
  const [active, setActive] = useState(0);
  const debounced = useDebounced(query.trim(), DEBOUNCE_MS);
  const result = useQuery({
    queryKey: ["search", debounced, DIALOG_LIMIT],
    queryFn: () => search(debounced, DIALOG_LIMIT),
    enabled: debounced.length > 0,
    placeholderData: keepPreviousData,
    staleTime: 10_000,
  });
  const data = debounced ? result.data : undefined;
  const words = wordsOf(debounced);
  // Tickets, then documents, then projects; the arrow keys walk through them in this order.
  const groups: Group[] = data
    ? [
        {
          kind: "i",
          label: m.issues,
          entries: data.issues.map((hit) => ({ id: hit.id, href: issueHref(hit), node: <IssueHitContent hit={hit} words={words} /> })),
        },
        {
          kind: "d",
          label: m.documents,
          entries: data.documents.map((hit) => ({ id: hit.id, href: documentHref(hit), node: <DocumentHitContent hit={hit} words={words} /> })),
        },
        {
          kind: "p",
          label: m.projects,
          entries: data.projects.map((hit) => ({ id: hit.id, href: projectHref(hit), node: <ProjectHitContent hit={hit} words={words} /> })),
        },
      ].filter((group) => group.entries.length > 0)
    : [];
  const items: Item[] = groups.flatMap((group) => group.entries.map((entry) => ({ id: `${listId}-${group.kind}-${entry.id}`, href: entry.href })));
  const current = items.length > 0 ? Math.min(active, items.length - 1) : -1;

  const activeId = items[current]?.id;

  useEffect(() => {
    if (activeId) document.getElementById(activeId)?.scrollIntoView({ block: "nearest" });
  }, [activeId]);

  const go = (href: string) => {
    onClose();
    router.push(href);
  };

  const onKeyDown = (event: React.KeyboardEvent<HTMLInputElement>) => {
    if (event.nativeEvent.isComposing) return;
    if (event.key === "ArrowDown" || event.key === "ArrowUp") {
      event.preventDefault();
      if (items.length === 0) return;
      const step = event.key === "ArrowDown" ? 1 : -1;
      setActive((current < 0 ? 0 : current + step + items.length) % items.length);
    } else if (event.key === "Enter") {
      event.preventDefault();
      const item = items[current];
      if (item) go(item.href);
      else if (query.trim()) go(searchPageHref(query));
    }
  };

  const optionClass = (selected: boolean) =>
    cn("flex w-full cursor-pointer items-start gap-2 rounded-md px-2.5 py-2 text-left", selected && "bg-accent text-accent-foreground");

  return (
    <>
      <div className="flex items-center gap-2.5 border-b px-4">
        <SearchIcon className="size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
        <input
          autoFocus
          role="combobox"
          aria-expanded={items.length > 0}
          aria-controls={listId}
          aria-activedescendant={activeId}
          aria-autocomplete="list"
          aria-label={m.inputLabel}
          placeholder={m.placeholder}
          maxLength={SEARCH_MAX_LENGTH}
          autoComplete="off"
          spellCheck={false}
          value={query}
          onChange={(event) => {
            setQuery(event.target.value);
            setActive(0);
          }}
          onKeyDown={onKeyDown}
          className="h-12 min-w-0 flex-1 bg-transparent text-[0.9375rem] outline-none placeholder:text-muted-foreground max-sm:h-14"
        />
        {result.isFetching ? <Spinner className="size-4 text-muted-foreground" aria-hidden="true" /> : null}
        <Kbd className="max-sm:hidden">Esc</Kbd>
      </div>

      <div id={listId} role="listbox" aria-label={m.resultsLabel} className="min-h-0 flex-1 overflow-y-auto overscroll-contain p-2">
        {!debounced ? (
          <p className="px-2.5 py-6 text-center text-[0.8125rem] leading-5 text-muted-foreground">{m.emptyHint}</p>
        ) : data && items.length === 0 ? (
          <p className="px-2.5 py-6 text-center text-[0.8125rem] leading-5 text-muted-foreground">{m.noResults(debounced)}</p>
        ) : data ? (
          <>
            {groups.map((group, groupIndex) => {
              // Where the group's first line is among all the lines of the list.
              const first = groups.slice(0, groupIndex).reduce((count, other) => count + other.entries.length, 0);
              return (
                <div key={group.kind} role="group" aria-label={group.label} className={groupIndex > 0 ? "mt-2" : undefined}>
                  <p className="px-2.5 pt-1 pb-1.5 text-xs font-medium text-muted-foreground">{group.label}</p>
                  {group.entries.map((entry, position) => {
                    const mine = first + position;
                    return (
                      <div
                        key={entry.id}
                        id={items[mine].id}
                        role="option"
                        aria-selected={mine === current}
                        className={optionClass(mine === current)}
                        onMouseMove={() => setActive(mine)}
                        onClick={() => go(entry.href)}
                      >
                        {entry.node}
                      </div>
                    );
                  })}
                </div>
              );
            })}
          </>
        ) : null}
      </div>

      <div className="flex items-center justify-between gap-3 border-t px-4 py-2 text-xs text-muted-foreground">
        <span className="flex items-center gap-3 max-sm:hidden">
          <span className="flex items-center gap-1">
            <KbdGroup>
              <Kbd>↑</Kbd>
              <Kbd>↓</Kbd>
            </KbdGroup>
            {m.move}
          </span>
          <span className="flex items-center gap-1">
            <Kbd>
              <CornerDownLeftIcon aria-hidden="true" />
            </Kbd>
            {m.open}
          </span>
        </span>
        <Link
          href={searchPageHref(query)}
          onClick={onClose}
          className="rounded-sm font-medium text-foreground outline-none hover:underline focus-visible:ring-3 focus-visible:ring-ring/50"
        >
          {data && (data.issuesMore || data.documentsMore || data.projectsMore) ? m.seeAllMore : m.seeAll}
        </Link>
      </div>
    </>
  );
}

/** Opens the search dialog. `sidebar` is the row in the sidebar; `icon` fits the narrow top bar. */
export function SearchButton({ variant }: { variant: "sidebar" | "icon" }) {
  const show = useContext(SearchContext);
  if (!show) return null;
  if (variant === "icon") {
    return (
      <Button variant="ghost" size="icon-lg" className="size-11" aria-label={m.title} onClick={show}>
        <SearchIcon />
      </Button>
    );
  }
  return (
    <button
      type="button"
      onClick={show}
      className="flex h-7 w-full items-center gap-2 rounded-md px-2 text-[0.8125rem] leading-5 text-muted-foreground outline-none hover:bg-sidebar-accent hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50 max-md:h-9"
    >
      <SearchIcon className="size-4" aria-hidden="true" />
      {m.title}
      <KbdGroup className="ml-auto">
        <Kbd>
          <ModKey />
        </Kbd>
        <Kbd>K</Kbd>
      </KbdGroup>
    </button>
  );
}
