"use client";

import { useState } from "react";
import { PlusIcon, TagIcon } from "lucide-react";
import { MultiCombobox } from "@/components/multi-combobox";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { apiErrorMessage } from "@/lib/api/error-message";
import { useRefreshingMutation } from "@/lib/use-refreshing-mutation";
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
 * The labels of an issue as a chip. It opens a combobox to tick any number of the workspace's
 * labels; typing narrows the list, and a name that does not exist yet can be added on the spot.
 */
export function LabelChip({
  labels,
  value,
  onChange,
  disabled,
  asPills,
}: {
  /** Every label in the workspace. */
  labels: Label[];
  /** Ids of the labels on the issue. */
  value: string[];
  onChange: (labelIds: string[]) => void;
  disabled?: boolean;
  /** Show the chosen labels as pills in a row, with a "+" that opens the list. For side panels. */
  asPills?: boolean;
}) {
  const [open, setOpen] = useState(false);
  // Labels made here show at once; the list from the server catches up with the refresh.
  const [added, setAdded] = useState<Label[]>([]);
  const all = [...labels, ...added.filter((label) => !labels.some((known) => known.id === label.id))];
  const selected = all.filter((label) => value.includes(label.id));
  const create = useRefreshingMutation(createLabel, (label) => {
    setAdded((current) => [...current, label]);
    if (!value.includes(label.id)) onChange([...value, label.id]);
  });

  return (
    <Popover open={open} onOpenChange={setOpen}>
      {asPills ? (
        <div className="flex flex-wrap items-center gap-1.5">
          {selected.map((label) => (
            <LabelPill key={label.id} label={label} />
          ))}
          <PopoverTrigger
            render={
              <button
                type="button"
                disabled={disabled}
                aria-label={`${m.editLabels}: ${selected.map((label) => label.name).join(", ") || m.noLabels}`}
                title={m.editLabels}
                // Hidden for people who may not edit, but never while its list is open: the button is
                // also disabled for the moment a change is being saved, and a hidden anchor would
                // send the open list to the corner of the window.
                className="flex size-6 items-center justify-center rounded-full text-muted-foreground outline-none hover:bg-muted hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50 aria-expanded:bg-muted max-md:size-9 [&:disabled:not([aria-expanded=true])]:hidden"
              />
            }
          >
            <PlusIcon className="size-3.5" aria-hidden="true" />
          </PopoverTrigger>
          {selected.length === 0 && disabled ? <span className="text-[0.8125rem] leading-5 text-muted-foreground">{m.noLabels}</span> : null}
        </div>
      ) : (
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
      )}
      <PopoverContent className="w-60 p-0">
        <MultiCombobox
          options={all.map((label) => ({ id: label.id, label: label.name, icon: <LabelDot color={label.color} /> }))}
          value={value}
          onChange={onChange}
          onCreate={(name) => create.mutate(name)}
          creating={create.isPending}
          placeholder={m.labelSearchPlaceholder}
          listLabel={m.labels}
          heading={m.workspaceLabels}
          emptyText={m.noLabels}
          createText={m.createLabel}
          error={create.isError ? apiErrorMessage(create.error) : null}
        />
      </PopoverContent>
    </Popover>
  );
}

/**
 * The label filter of an issue list: any number of labels, and "no label". An issue passes when
 * it has one of the ticked labels. Labels are not made here.
 */
export function LabelFilterChip({
  labels,
  value,
  onChange,
  noneValue,
}: {
  labels: Label[];
  /** Ids of the ticked labels; `noneValue` stands for issues without a label. */
  value: string[];
  onChange: (value: string[]) => void;
  noneValue: string;
}) {
  const options = [
    { id: noneValue, label: m.noLabels, icon: <TagIcon className="size-4 shrink-0 text-muted-foreground" aria-hidden="true" /> },
    ...labels.map((label) => ({ id: label.id, label: label.name, icon: <LabelDot color={label.color} /> })),
  ];
  const selected = options.filter((option) => value.includes(option.id));

  return (
    <Popover>
      <PopoverTrigger
        render={
          <button
            type="button"
            aria-label={`${m.labelFilter}: ${selected.map((option) => option.label).join(", ") || m.allLabels}`}
            className={chipClass}
          />
        }
      >
        {selected.length === 1 ? selected[0].icon : <TagIcon className="size-4 shrink-0 text-muted-foreground" aria-hidden="true" />}
        <span className="truncate">
          {selected.length === 0 ? m.allLabels : selected.length === 1 ? selected[0].label : m.labelCount(selected.length)}
        </span>
      </PopoverTrigger>
      <PopoverContent className="w-60 p-0">
        <MultiCombobox
          options={options}
          value={value}
          onChange={onChange}
          placeholder={m.labelFilterPlaceholder}
          listLabel={m.labels}
          heading={m.workspaceLabels}
          emptyText={m.noLabelsFound}
        />
      </PopoverContent>
    </Popover>
  );
}
