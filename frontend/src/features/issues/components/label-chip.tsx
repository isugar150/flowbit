"use client";

import { useState } from "react";
import { CheckIcon, PlusIcon, TagIcon } from "lucide-react";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { apiErrorMessage } from "@/lib/api/error-message";
import { useRefreshingMutation } from "@/lib/use-refreshing-mutation";
import { cn } from "@/lib/utils";
import { messages } from "@/messages";
import { createLabel } from "../api";
import type { Label } from "../types";
import { chipClass } from "./chip-select";

const m = messages.issues;

export function LabelDot({ color }: { color: string }) {
  return <span aria-hidden="true" className="size-2.5 shrink-0 rounded-full" style={{ backgroundColor: color }} />;
}

/** One label as a small pill, for list rows. */
export function LabelPill({ label }: { label: Label }) {
  return (
    <span className="flex h-6 max-w-32 shrink-0 items-center gap-1.5 rounded-full border px-2 text-xs leading-4">
      <LabelDot color={label.color} />
      <span className="truncate">{label.name}</span>
    </span>
  );
}

/**
 * The labels of an issue as a chip. It opens a list to tick any number of the workspace's labels;
 * typing narrows the list, and a name that does not exist yet can be added on the spot.
 */
export function LabelChip({
  labels,
  value,
  onChange,
  disabled,
}: {
  /** Every label in the workspace. */
  labels: Label[];
  /** Ids of the labels on the issue. */
  value: string[];
  onChange: (labelIds: string[]) => void;
  disabled?: boolean;
}) {
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState("");
  // Labels made here show at once; the list from the server catches up with the refresh.
  const [added, setAdded] = useState<Label[]>([]);
  const all = [...labels, ...added.filter((label) => !labels.some((known) => known.id === label.id))];
  const selected = all.filter((label) => value.includes(label.id));
  const name = query.trim();
  const shown = all.filter((label) => label.name.toLowerCase().includes(name.toLowerCase()));
  const exists = all.some((label) => label.name.toLowerCase() === name.toLowerCase());
  const create = useRefreshingMutation(createLabel, (label) => {
    setAdded((current) => [...current, label]);
    setQuery("");
    if (!value.includes(label.id)) onChange([...value, label.id]);
  });
  const toggle = (id: string) => onChange(value.includes(id) ? value.filter((item) => item !== id) : [...value, id]);

  return (
    <Popover
      open={open}
      onOpenChange={(next) => {
        setOpen(next);
        if (!next) setQuery("");
      }}
    >
      <PopoverTrigger
        render={
          <button
            type="button"
            disabled={disabled}
            aria-label={`${m.labels}: ${selected.map((label) => label.name).join(", ") || m.noLabels}`}
            className={chipClass}
          />
        }
      >
        {selected.length === 0 ? (
          <>
            <TagIcon className="size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
            <span className="truncate text-muted-foreground">{m.noLabels}</span>
          </>
        ) : (
          <>
            <span className="flex shrink-0 -space-x-1">
              {selected.slice(0, 3).map((label) => (
                <LabelDot key={label.id} color={label.color} />
              ))}
            </span>
            <span className="truncate">{selected.length === 1 ? selected[0].name : m.labelCount(selected.length)}</span>
          </>
        )}
      </PopoverTrigger>
      <PopoverContent className="flex w-56 flex-col gap-1 p-1">
        <input
          value={query}
          placeholder={m.labelSearchPlaceholder}
          aria-label={m.labelSearchPlaceholder}
          maxLength={30}
          autoComplete="off"
          autoFocus
          onChange={(event) => setQuery(event.target.value)}
          onKeyDown={(event) => {
            if (event.key !== "Enter" || event.nativeEvent.isComposing) return;
            // Enter must not submit the form the chip sits in (the new issue window).
            event.preventDefault();
            if (name && !exists && !create.isPending) create.mutate(name);
            else if (shown.length === 1) toggle(shown[0].id);
          }}
          className="h-8 w-full rounded-md bg-transparent px-2 text-[0.8125rem] leading-5 outline-none placeholder:text-muted-foreground"
        />
        <ul role="listbox" aria-label={m.labels} aria-multiselectable="true" className="flex max-h-60 flex-col overflow-y-auto border-t pt-1">
          {shown.map((label) => {
            const checked = value.includes(label.id);
            return (
              <li key={label.id} role="option" aria-selected={checked}>
                <button
                  type="button"
                  onClick={() => toggle(label.id)}
                  className="flex h-8 w-full items-center gap-2 rounded-md px-2 text-left text-[0.8125rem] leading-5 outline-none hover:bg-accent focus-visible:bg-accent max-md:h-10"
                >
                  <span
                    aria-hidden="true"
                    className={cn(
                      "flex size-4 shrink-0 items-center justify-center rounded border",
                      checked && "border-primary bg-primary text-primary-foreground",
                    )}
                  >
                    {checked ? <CheckIcon className="size-3" strokeWidth={3} /> : null}
                  </span>
                  <LabelDot color={label.color} />
                  <span className="truncate">{label.name}</span>
                </button>
              </li>
            );
          })}
          {name && !exists ? (
            <li>
              <button
                type="button"
                disabled={create.isPending}
                onClick={() => create.mutate(name)}
                className="flex h-8 w-full items-center gap-2 rounded-md px-2 text-left text-[0.8125rem] leading-5 outline-none hover:bg-accent focus-visible:bg-accent disabled:opacity-50 max-md:h-10"
              >
                <PlusIcon className="size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
                <span className="truncate">{m.createLabel(name)}</span>
              </button>
            </li>
          ) : null}
          {shown.length === 0 && !name ? (
            <li className="px-2 py-1.5 text-[0.8125rem] leading-5 text-muted-foreground">{m.noLabels}</li>
          ) : null}
        </ul>
        {create.isError ? (
          <p role="alert" className="px-2 pb-1 text-xs leading-4 text-destructive">
            {apiErrorMessage(create.error)}
          </p>
        ) : null}
      </PopoverContent>
    </Popover>
  );
}
