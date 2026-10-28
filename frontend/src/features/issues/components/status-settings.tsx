"use client";

import { useState } from "react";
import { ArrowDownIcon, ArrowUpIcon, PlusIcon, Trash2Icon } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { FormAlert } from "@/features/auth/components/form-alert";
import { apiErrorMessage } from "@/lib/api/error-message";
import { useRefreshingMutation } from "@/lib/use-refreshing-mutation";
import { messages } from "@/messages";
import { createStatus, deleteStatus, renameStatus, moveStatus } from "../api";
import { StatusIcon } from "../meta";
import type { IssueStatusItem } from "../status-defaults";
import type { IssueStatus } from "../types";

const m = messages.issues.statusSettings;

const CATEGORIES: IssueStatus[] = ["BACKLOG", "READY", "IN_PROGRESS", "DONE", "CANCELED"];

/** One status: its name (saved when the box is left), the two arrows that move it inside its category, and the delete. */
function StatusLine({ status, first, last, only }: { status: IssueStatusItem; first: boolean; last: boolean; only: boolean }) {
  const [name, setName] = useState(status.name);
  const [error, setError] = useState<string | null>(null);
  const rename = useRefreshingMutation(renameStatus);
  const move = useRefreshingMutation(moveStatus);
  const remove = useRefreshingMutation(deleteStatus);
  const [confirming, setConfirming] = useState(false);
  const fail = (e: unknown) => setError(apiErrorMessage(e));

  return (
    <li className="flex flex-col gap-1">
      <div className="flex items-center gap-2">
        <StatusIcon status={status.category} />
        <Input
          aria-label={m.nameLabel(status.name)}
          value={name}
          maxLength={30}
          className="h-8 max-w-64"
          onChange={(event) => setName(event.target.value)}
          onBlur={() => {
            const next = name.trim();
            if (next === status.name) return;
            if (!next) {
              setName(status.name);
              return;
            }
            setError(null);
            rename.mutate({ id: status.id, name: next }, { onError: (e) => { fail(e); setName(status.name); } });
          }}
        />
        <Button variant="ghost" size="icon-sm" aria-label={m.up(status.name)} disabled={first || move.isPending} onClick={() => move.mutate({ id: status.id, direction: "UP" }, { onError: fail })}>
          <ArrowUpIcon />
        </Button>
        <Button variant="ghost" size="icon-sm" aria-label={m.down(status.name)} disabled={last || move.isPending} onClick={() => move.mutate({ id: status.id, direction: "DOWN" }, { onError: fail })}>
          <ArrowDownIcon />
        </Button>
        {confirming ? (
          <>
            <Button variant="destructive" size="sm" disabled={remove.isPending} onClick={() => remove.mutate(status.id, { onError: fail })}>
              {m.confirmDelete}
            </Button>
            <Button variant="ghost" size="sm" onClick={() => setConfirming(false)}>
              {m.cancel}
            </Button>
          </>
        ) : (
          <Button variant="ghost" size="icon-sm" aria-label={m.delete(status.name)} disabled={only} onClick={() => setConfirming(true)}>
            <Trash2Icon />
          </Button>
        )}
      </div>
      {error ? <FormAlert>{error}</FormAlert> : null}
    </li>
  );
}

/** Adds a status inside one category. */
function AddStatus({ category }: { category: IssueStatus }) {
  const [name, setName] = useState("");
  const [error, setError] = useState<string | null>(null);
  const create = useRefreshingMutation(createStatus, () => setName(""));

  return (
    <form
      className="flex items-center gap-2 pt-1"
      onSubmit={(event) => {
        event.preventDefault();
        const next = name.trim();
        if (!next) return;
        setError(null);
        create.mutate({ category, name: next }, { onError: (e) => setError(apiErrorMessage(e)) });
      }}
    >
      <Input aria-label={m.addLabel(messages.issues.statuses[category])} value={name} maxLength={30} placeholder={m.addPlaceholder} className="h-8 max-w-64" onChange={(event) => setName(event.target.value)} />
      <Button type="submit" variant="outline" size="sm" disabled={!name.trim() || create.isPending}>
        <PlusIcon data-icon="inline-start" />
        {m.add}
      </Button>
      {error ? <span role="alert" className="text-[0.8125rem] leading-5 text-destructive">{error}</span> : null}
    </form>
  );
}

/** The statuses by category, to rename, reorder, add and delete (docs/features/statuses-and-estimates.md). */
export function StatusSettings({ statuses }: { statuses: IssueStatusItem[] }) {
  return (
    <div className="flex flex-col gap-6">
      {CATEGORIES.map((category) => {
        const inCategory = statuses.filter((status) => status.category === category);
        return (
          <section key={category} className="flex flex-col gap-2">
            <h3 className="text-[0.8125rem] leading-5 font-medium text-muted-foreground">
              {messages.issues.statuses[category]} <span className="font-normal">{m.categoryHint[category]}</span>
            </h3>
            <ul className="flex flex-col gap-2">
              {inCategory.map((status, index) => (
                <StatusLine key={status.id} status={status} first={index === 0} last={index === inCategory.length - 1} only={inCategory.length === 1} />
              ))}
            </ul>
            <AddStatus category={category} />
          </section>
        );
      })}
    </div>
  );
}
