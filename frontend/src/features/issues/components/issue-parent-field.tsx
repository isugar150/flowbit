"use client";

import { useId, useState } from "react";
import Link from "next/link";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { CornerLeftUpIcon, PencilIcon, SearchIcon, XIcon } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { Spinner } from "@/components/ui/spinner";
import { FormAlert } from "@/features/auth/components/form-alert";
import { SEARCH_MAX_LENGTH, search } from "@/features/search/api";
import { useDebounced } from "@/features/search/components/search-dialog";
import { Highlight, wordsOf } from "@/features/search/highlight";
import { apiErrorMessage } from "@/lib/api/error-message";
import { useRefreshingMutation } from "@/lib/use-refreshing-mutation";
import { cn } from "@/lib/utils";
import { messages } from "@/messages";
import { updateIssue } from "../api";
import { StatusIcon, statusLabel } from "../meta";
import type { Issue } from "../types";
import { plainChipClass } from "./chip-select";
import { IssuePath } from "./issue-path";

const m = messages.issues.parentField;

/** How many candidates the list shows at most; narrowing the words finds the rest. */
const CANDIDATES = 8;
/** How long typing must pause before a search goes out. */
const DEBOUNCE_MS = 150;

/**
 * The issue this one hangs under, in the side panel: the parent's key and title, and, to change
 * it, a search over every issue the person can see (D-189). Each candidate shows the issues
 * above it from the top, so two issues with the same title are told apart by where they hang.
 * Picking one makes this issue its sub-issue (its project then follows the parent's, D-129);
 * "상위 티켓 해제" makes it a top-level issue again. The issue itself and the issues below it are
 * not offered: they would make a loop. The parent is a link; the separate edit button opens the picker.
 */
export function IssueParentField({ issue, canEdit }: { issue: Pick<Issue, "number" | "key" | "parent" | "ancestors">; canEdit: boolean }) {
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState("");
  const [active, setActive] = useState(0);
  const listId = useId();
  const debounced = useDebounced(query.trim(), DEBOUNCE_MS);
  const result = useQuery({
    queryKey: ["parent-candidates", debounced],
    queryFn: () => search(debounced, CANDIDATES + 2),
    enabled: open && debounced.length > 0,
    placeholderData: keepPreviousData,
    staleTime: 10_000,
  });
  const mutation = useRefreshingMutation(updateIssue, () => {
    setOpen(false);
    setQuery("");
  });

  const parent = issue.parent;
  const pathText = issue.ancestors.map((ancestor) => `${ancestor.key} ${ancestor.title}`).join(" › ");
  const words = wordsOf(debounced);
  // Not itself, not what already hangs under it (its path has this issue in it), not the parent it already has.
  const candidates = (debounced && result.data ? result.data.issues : [])
    .filter((hit) => hit.key !== issue.key && hit.key !== parent?.key && !hit.ancestors.some((ancestor) => ancestor.key === issue.key))
    .slice(0, CANDIDATES);
  const current = candidates.length > 0 ? Math.min(active, candidates.length - 1) : -1;

  const pick = (id: string | null) => {
    if (mutation.isPending) return;
    mutation.mutate({ number: issue.number, changes: { parentId: id } });
  };

  const rowText = parent ? (
    <>
      <span className="shrink-0 text-muted-foreground tabular-nums">{parent.key}</span>
      <span className="truncate">{parent.title}</span>
    </>
  ) : (
    <span className="truncate font-normal text-muted-foreground">{m.none}</span>
  );

  if (!canEdit) {
    return parent ? (
      <Link
        href={`/issue/${parent.key}`}
        title={pathText}
        className="-mx-2 flex h-8 min-w-0 items-center gap-2 rounded-md px-2 text-[0.8125rem] leading-5 font-medium outline-none hover:bg-muted focus-visible:ring-3 focus-visible:ring-ring/50 max-md:h-10"
      >
        <CornerLeftUpIcon className="size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
        {rowText}
      </Link>
    ) : (
      <span className="flex h-8 items-center gap-2 text-[0.8125rem] leading-5 text-muted-foreground max-md:h-10">
        <CornerLeftUpIcon className="size-4 shrink-0" aria-hidden="true" />
        {m.none}
      </span>
    );
  }

  return (
    <div className="flex min-w-0 items-center gap-1">
      {parent ? (
        <Link href={`/issue/${parent.key}`} aria-label={m.open(parent.key)} title={pathText} className={cn(plainChipClass, "min-w-0 flex-1")}><CornerLeftUpIcon className="size-4 shrink-0 text-muted-foreground" aria-hidden="true" />{rowText}</Link>
      ) : null}
      <Popover
        open={open}
        onOpenChange={(next) => {
          setOpen(next);
          if (!next) {
            setQuery("");
            setActive(0);
            mutation.reset();
          }
        }}
      >
        <PopoverTrigger
          render={<button type="button" aria-label={parent ? m.change : m.pick} title={parent ? m.change : m.pick} className={cn(plainChipClass, parent ? "h-8 w-8 shrink-0 justify-center max-md:h-11 max-md:w-11" : "w-full min-w-0")} />}
        >
          {parent ? <PencilIcon className="size-4 text-muted-foreground" aria-hidden="true" /> : <><CornerLeftUpIcon className="size-4 shrink-0 text-muted-foreground" aria-hidden="true" />{rowText}</>}
        </PopoverTrigger>
        <PopoverContent align="start" className="w-80 p-0">
          <div className="flex items-center gap-2 border-b px-3">
            <SearchIcon className="size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
            <input
              autoFocus
              role="combobox"
              aria-expanded={candidates.length > 0}
              aria-controls={listId}
              aria-activedescendant={current >= 0 ? `${listId}-${current}` : undefined}
              aria-autocomplete="list"
              aria-label={m.searchLabel}
              placeholder={m.searchPlaceholder}
              maxLength={SEARCH_MAX_LENGTH}
              autoComplete="off"
              spellCheck={false}
              value={query}
              onChange={(event) => {
                setQuery(event.target.value);
                setActive(0);
              }}
              onKeyDown={(event) => {
                if (event.nativeEvent.isComposing) return;
                if (event.key === "ArrowDown" || event.key === "ArrowUp") {
                  event.preventDefault();
                  if (candidates.length === 0) return;
                  const step = event.key === "ArrowDown" ? 1 : -1;
                  setActive((current < 0 ? 0 : current + step + candidates.length) % candidates.length);
                } else if (event.key === "Enter") {
                  event.preventDefault();
                  if (current >= 0) pick(candidates[current].id);
                }
              }}
              className="h-10 min-w-0 flex-1 bg-transparent text-[0.8125rem] leading-5 outline-none placeholder:text-muted-foreground max-md:h-12"
            />
            {result.isFetching ? <Spinner className="size-4 text-muted-foreground" aria-label={m.searching} /> : null}
          </div>
          <div id={listId} role="listbox" aria-label={m.resultsLabel} className="max-h-72 overflow-y-auto overscroll-contain p-1.5">
            {!debounced ? (
              <p className="px-2 py-4 text-center text-xs leading-4 text-muted-foreground">{m.emptyHint}</p>
            ) : result.data && candidates.length === 0 ? (
              <p className="px-2 py-4 text-center text-xs leading-4 text-muted-foreground">{m.noResults(debounced)}</p>
            ) : (
              candidates.map((hit, index) => (
                <div
                  key={hit.id}
                  id={`${listId}-${index}`}
                  role="option"
                  aria-selected={index === current}
                  onMouseMove={() => setActive(index)}
                  onClick={() => pick(hit.id)}
                  className={cn(
                    "flex cursor-pointer flex-col gap-0.5 rounded-md px-2 py-1.5 text-[0.8125rem] leading-5",
                    index === current && "bg-accent text-accent-foreground",
                  )}
                >
                  <span className="flex min-w-0 items-center gap-2">
                    <StatusIcon status={hit.status} />
                    <span className="sr-only">{statusLabel(hit.status)}</span>
                    <span className="shrink-0 text-xs text-muted-foreground tabular-nums">{hit.key}</span>
                    <span className="min-w-0 truncate font-medium">
                      <Highlight text={hit.title} words={words} />
                    </span>
                  </span>
                  {/* From the top-level issue down, so where it hangs is plain before it is picked. */}
                  <IssuePath ancestors={hit.ancestors} className="pl-6" />
                </div>
              ))
            )}
          </div>
          {mutation.isError ? (
            <div className="border-t p-2">
              <FormAlert>{apiErrorMessage(mutation.error)}</FormAlert>
            </div>
          ) : null}
          <div className="flex items-center justify-between gap-2 border-t px-3 py-2">
            <p className="min-w-0 text-xs leading-4 text-muted-foreground">{m.hint}</p>
            {parent ? (
              <Button type="button" variant="ghost" size="sm" className="shrink-0 max-md:h-10" disabled={mutation.isPending} onClick={() => pick(null)}>
                <XIcon data-icon="inline-start" />
                {m.remove}
              </Button>
            ) : null}
          </div>
        </PopoverContent>
      </Popover>
    </div>
  );
}
