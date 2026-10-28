"use client";

import { useId, useState } from "react";
import Link from "next/link";
import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { PlusIcon, SearchIcon, XIcon } from "lucide-react";
import { Button } from "@/components/ui/button";
import { NativeSelect, NativeSelectOption } from "@/components/ui/native-select";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { Spinner } from "@/components/ui/spinner";
import { FormAlert } from "@/features/auth/components/form-alert";
import { SEARCH_MAX_LENGTH, search } from "@/features/search/api";
import { useDebounced } from "@/features/search/components/search-dialog";
import type { IssueHit } from "@/features/search/types";
import { apiFetch } from "@/lib/api/client";
import { ApiError } from "@/lib/api/errors";
import { apiErrorMessage } from "@/lib/api/error-message";
import { cn } from "@/lib/utils";
import { messages } from "@/messages";
import { StatusIcon } from "../meta";
import type { IssueStatus } from "../types";

const m = messages.issues.relations;

export type RelationKind = "BLOCKS" | "BLOCKED_BY" | "DUPLICATE_OF" | "DUPLICATED_BY" | "RELATED";

type Relation = {
  id: string;
  kind: RelationKind;
  issue: { number: number; key: string; title: string; status: IssueStatus; statusName: string };
};

/** The order the kinds are listed in: what holds this issue up first. */
const KIND_ORDER: RelationKind[] = ["BLOCKED_BY", "BLOCKS", "DUPLICATE_OF", "DUPLICATED_BY", "RELATED"];
/** What a person can say about a new relation, from this issue's point of view. */
const ADDABLE: Exclude<RelationKind, "DUPLICATED_BY">[] = ["BLOCKED_BY", "BLOCKS", "DUPLICATE_OF", "RELATED"];

/** The kinds a relation can be asked for with. */
export type AddableKind = (typeof ADDABLE)[number];
/** The issue on the other end of a relation, as the search finds it. */
export type RelationTarget = Pick<IssueHit, "number" | "key" | "title" | "status">;

const CANDIDATES = 8;
const DEBOUNCE_MS = 150;

/** Why a pick failed: the server's sentence, or the one the caller refused it with. */
function problemOf(error: unknown): string {
  return error instanceof Error && !(error instanceof ApiError) ? error.message : apiErrorMessage(error);
}

/**
 * The popover that finds the other issue and says how it relates (docs/features/issue-relations.md). It only picks: what is done with
 * the pick is `onPick`'s. The popover closes once that resolves; if it rejects, the reason is shown and the popover stays.
 */
export function RelationPicker({
  onPick,
  exclude,
  fixedKind,
  triggerLabel,
}: {
  onPick: (kind: AddableKind, target: RelationTarget) => Promise<unknown> | void;
  /** An issue that is not offered: the one the relation starts from. */
  exclude?: number;
  /** Only this kind is offered (the triage queue's "duplicate"). */
  fixedKind?: AddableKind;
  /** A button with these words instead of the "+" icon. */
  triggerLabel?: string;
}) {
  const [open, setOpen] = useState(false);
  const [chosen, setChosen] = useState<AddableKind>("BLOCKED_BY");
  const kind = fixedKind ?? chosen;
  const [query, setQuery] = useState("");
  const [pending, setPending] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const listId = useId();
  const debounced = useDebounced(query.trim(), DEBOUNCE_MS);
  const result = useQuery({
    queryKey: ["relation-candidates", debounced],
    queryFn: () => search(debounced, CANDIDATES + 2),
    enabled: open && debounced.length > 0,
    placeholderData: keepPreviousData,
    staleTime: 10_000,
  });
  // The tour's example screens answer a search with nothing at all, so the list may be missing.
  const candidates = (debounced ? (result.data?.issues ?? []) : []).filter((hit) => hit.number !== exclude).slice(0, CANDIDATES);

  const pick = async (hit: RelationTarget) => {
    setPending(true);
    setProblem(null);
    try {
      await onPick(kind, hit);
      setOpen(false);
      setQuery("");
    } catch (error) {
      setProblem(problemOf(error));
    } finally {
      setPending(false);
    }
  };

  return (
    <Popover
      open={open}
      onOpenChange={(next) => {
        setOpen(next);
        if (!next) {
          setQuery("");
          setProblem(null);
        }
      }}
    >
      <PopoverTrigger
        render={
          triggerLabel ? (
            <Button type="button" variant="outline" size="sm" className="max-md:h-11" />
          ) : (
            <Button type="button" variant="ghost" size="icon-sm" aria-label={m.add} title={m.add} />
          )
        }
      >
        {triggerLabel ?? <PlusIcon />}
      </PopoverTrigger>
      <PopoverContent align="end" className="w-80 p-0">
        <div className="flex flex-col gap-2 border-b p-3">
          {fixedKind ? null : (
            <NativeSelect aria-label={m.kindLabel} value={chosen} onChange={(event) => setChosen(event.target.value as AddableKind)} className="w-full">
              {ADDABLE.map((value) => (
                <NativeSelectOption key={value} value={value}>
                  {m.kindsToAdd[value]}
                </NativeSelectOption>
              ))}
            </NativeSelect>
          )}
          <div className="flex items-center gap-2 rounded-md border px-2">
            <SearchIcon className="size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
            <input
              autoFocus
              aria-label={m.searchLabel}
              placeholder={m.searchPlaceholder}
              maxLength={SEARCH_MAX_LENGTH}
              autoComplete="off"
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              className="h-9 min-w-0 flex-1 bg-transparent text-[0.8125rem] leading-5 outline-none placeholder:text-muted-foreground"
            />
            {result.isFetching ? <Spinner className="size-4 text-muted-foreground" aria-label={m.searching} /> : null}
          </div>
        </div>
        <div id={listId} role="listbox" aria-label={m.resultsLabel} className="max-h-64 overflow-y-auto overscroll-contain p-1.5">
          {!debounced ? (
            <p className="px-2 py-4 text-center text-xs leading-4 text-muted-foreground">{m.emptyHint}</p>
          ) : result.data && candidates.length === 0 ? (
            <p className="px-2 py-4 text-center text-xs leading-4 text-muted-foreground">{m.noResults(debounced)}</p>
          ) : (
            candidates.map((hit) => (
              <button
                key={hit.id}
                type="button"
                role="option"
                aria-selected={false}
                disabled={pending}
                onClick={() => void pick(hit)}
                className="flex w-full min-w-0 items-center gap-2 rounded-md px-2 py-1.5 text-left text-[0.8125rem] leading-5 outline-none hover:bg-accent focus-visible:bg-accent"
              >
                <StatusIcon status={hit.status} />
                <span className="shrink-0 text-xs text-muted-foreground tabular-nums">{hit.key}</span>
                <span className="min-w-0 truncate font-medium">{hit.title}</span>
              </button>
            ))
          )}
        </div>
        {problem ? (
          <div className="border-t p-2">
            <FormAlert>{problem}</FormAlert>
          </div>
        ) : null}
      </PopoverContent>
    </Popover>
  );
}

/** The picker of an issue that exists: the pick is saved as a relation of that issue at once. */
export function AddRelation({
  number,
  onAdded,
  fixedKind,
  triggerLabel,
}: {
  number: number;
  onAdded: (relations: Relation[]) => void;
  fixedKind?: AddableKind;
  triggerLabel?: string;
}) {
  return (
    <RelationPicker
      exclude={number}
      fixedKind={fixedKind}
      triggerLabel={triggerLabel}
      onPick={async (kind, target) => {
        onAdded(await apiFetch<Relation[]>(`/api/v1/issues/${number}/relations`, { method: "POST", body: { kind, targetNumber: target.number } }));
      }}
    />
  );
}

/**
 * The relations of an issue, under its sub-issues: what blocks it, what it blocks, the issue it duplicates or that duplicate it, and
 * the related ones. Anyone who can edit the issue adds and removes them.
 */
export function IssueRelations({ number, canEdit }: { number: number; canEdit: boolean }) {
  const client = useQueryClient();
  const key = ["issue-relations", number];
  const relations = useQuery({ queryKey: key, queryFn: () => apiFetch<Relation[]>(`/api/v1/issues/${number}/relations`), staleTime: 15_000 });
  const remove = useMutation({
    mutationFn: (id: string) => apiFetch<void>(`/api/v1/issues/${number}/relations/${id}`, { method: "DELETE" }),
    onSuccess: () => client.invalidateQueries({ queryKey: key }),
  });
  const rows = relations.data ?? [];
  if (rows.length === 0 && !canEdit) return null;

  return (
    <section data-presence-id="relations" aria-labelledby="relations-title" className="flex flex-col gap-1">
      <div className="flex h-8 items-center gap-2">
        <h3 id="relations-title" className="text-[0.8125rem] leading-5 font-medium text-muted-foreground">
          {m.heading}
        </h3>
        {canEdit ? (
          <span className="ml-auto">
            <AddRelation number={number} onAdded={(next) => client.setQueryData(key, next)} />
          </span>
        ) : null}
      </div>
      {remove.isError ? <FormAlert>{apiErrorMessage(remove.error)}</FormAlert> : null}
      {rows.length === 0 ? <p className="text-[0.8125rem] leading-5 text-muted-foreground">{m.none}</p> : null}
      {KIND_ORDER.map((kind) => {
        const ofKind = rows.filter((row) => row.kind === kind);
        if (ofKind.length === 0) return null;
        return (
          <div key={kind} className="flex flex-col">
            <p className="pt-1 text-xs leading-4 font-medium text-muted-foreground">{m.kinds[kind]}</p>
            <ul>
              {ofKind.map((row) => (
                <li key={row.id} className="flex h-9 items-center gap-2 text-[0.8125rem] leading-5">
                  <StatusIcon status={row.issue.status} />
                  <span className="sr-only">{row.issue.statusName}</span>
                  <Link
                    href={`/issue/${row.issue.key}`}
                    className={cn("flex min-w-0 flex-1 items-center gap-2 rounded-sm outline-none focus-visible:ring-3 focus-visible:ring-ring/50", (row.issue.status === "DONE" || row.issue.status === "CANCELED") && "text-muted-foreground line-through")}
                  >
                    <span className="shrink-0 text-xs text-muted-foreground tabular-nums">{row.issue.key}</span>
                    <span className="truncate font-medium">{row.issue.title}</span>
                  </Link>
                  {canEdit ? (
                    <Button type="button" variant="ghost" size="icon-sm" aria-label={m.remove(row.issue.key)} disabled={remove.isPending} onClick={() => remove.mutate(row.id)}>
                      <XIcon />
                    </Button>
                  ) : null}
                </li>
              ))}
            </ul>
          </div>
        );
      })}
    </section>
  );
}
