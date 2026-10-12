"use client";

import { useId, useState } from "react";
import { CheckIcon, PlusIcon } from "lucide-react";
import { cn } from "@/lib/utils";

export type ComboboxOption = {
  id: string;
  label: string;
  icon?: React.ReactNode;
};

const rowClass =
  "group flex h-8 cursor-default items-center gap-2 rounded-md px-2 text-[0.8125rem] leading-5 data-[active=true]:bg-accent max-md:h-10";

/**
 * A search field over a list where any number of options can be ticked, for the inside of a
 * popover. The options ticked when it opened stay on top, the rest follow under `heading`.
 * Typing narrows the list; with `onCreate`, a name that is not in the list can be added on the
 * spot. Arrow keys move, Enter ticks or adds.
 */
export function MultiCombobox({
  options,
  value,
  onChange,
  onCreate,
  creating,
  placeholder,
  listLabel,
  heading,
  emptyText,
  createText,
  error,
  maxLength = 30,
}: {
  options: ComboboxOption[];
  /** Ids of the ticked options. */
  value: string[];
  onChange: (value: string[]) => void;
  /** Adds what was typed as a new option. Leave out for a list that cannot grow. */
  onCreate?: (name: string) => void;
  creating?: boolean;
  placeholder: string;
  /** What the list holds, for screen readers. */
  listLabel: string;
  /** Title over the options that were not ticked when the list opened. */
  heading?: string;
  /** Shown when there is nothing to list. */
  emptyText: string;
  createText?: (name: string) => string;
  error?: string | null;
  maxLength?: number;
}) {
  const id = useId();
  const [query, setQuery] = useState("");
  // Fixed while open, so a row does not jump away from the pointer when it is ticked.
  const [pinned] = useState(() => new Set(value));
  const [active, setActive] = useState(0);

  const name = query.trim();
  const needle = name.toLowerCase();
  const shown = options.filter((option) => option.label.toLowerCase().includes(needle));
  const top = shown.filter((option) => pinned.has(option.id));
  const rest = shown.filter((option) => !pinned.has(option.id));
  const rows = [...top, ...rest];
  const canCreate = Boolean(onCreate) && name !== "" && !options.some((option) => option.label.toLowerCase() === needle);
  const count = rows.length + (canCreate ? 1 : 0);
  const current = Math.min(active, Math.max(count - 1, 0));

  const toggle = (optionId: string) =>
    onChange(value.includes(optionId) ? value.filter((item) => item !== optionId) : [...value, optionId]);
  const choose = (index: number) => {
    if (index < rows.length) {
      toggle(rows[index].id);
    } else if (canCreate && !creating) {
      onCreate?.(name);
      setQuery("");
      setActive(0);
    }
  };
  const move = (index: number) => {
    setActive(index);
    document.getElementById(`${id}-${index}`)?.scrollIntoView({ block: "nearest" });
  };

  const row = (option: ComboboxOption, index: number) => {
    const checked = value.includes(option.id);
    return (
      <div
        key={option.id}
        id={`${id}-${index}`}
        role="option"
        aria-selected={checked}
        data-active={index === current}
        className={rowClass}
        onPointerMove={() => setActive(index)}
        // Keeps the caret in the search field.
        onMouseDown={(event) => event.preventDefault()}
        onClick={() => choose(index)}
      >
        <span
          aria-hidden="true"
          className={cn(
            "flex size-4 shrink-0 items-center justify-center rounded border",
            checked ? "border-primary bg-primary text-primary-foreground" : "border-transparent group-data-[active=true]:border-input",
          )}
        >
          {checked ? <CheckIcon className="size-3" strokeWidth={3} /> : null}
        </span>
        {option.icon}
        <span className="truncate">{option.label}</span>
      </div>
    );
  };

  return (
    <div className="flex flex-col">
      <input
        role="combobox"
        aria-expanded="true"
        aria-controls={`${id}-list`}
        aria-activedescendant={count > 0 ? `${id}-${current}` : undefined}
        aria-autocomplete="list"
        aria-label={placeholder}
        value={query}
        placeholder={placeholder}
        maxLength={maxLength}
        autoComplete="off"
        autoFocus
        onChange={(event) => {
          setQuery(event.target.value);
          setActive(0);
        }}
        onKeyDown={(event) => {
          if (event.nativeEvent.isComposing) return;
          if (event.key === "ArrowDown" || event.key === "ArrowUp") {
            event.preventDefault();
            if (count > 0) move((current + (event.key === "ArrowDown" ? 1 : count - 1)) % count);
          } else if (event.key === "Enter") {
            // Enter must not submit the form the list sits in (the new issue window).
            event.preventDefault();
            if (count > 0) choose(current);
          }
        }}
        className="h-10 w-full border-b bg-transparent px-3 text-[0.8125rem] leading-5 outline-none placeholder:text-muted-foreground"
      />
      <div
        id={`${id}-list`}
        role="listbox"
        aria-label={listLabel}
        aria-multiselectable="true"
        className="flex max-h-64 flex-col overflow-y-auto p-1"
      >
        {top.map((option, index) => row(option, index))}
        {top.length > 0 && (rest.length > 0 || canCreate) ? <div role="presentation" className="-mx-1 my-1 border-t" /> : null}
        {rest.length > 0 && heading ? (
          <div role="presentation" className="px-2 py-1.5 text-xs leading-4 text-muted-foreground">
            {heading}
          </div>
        ) : null}
        {rest.map((option, index) => row(option, top.length + index))}
        {canCreate ? (
          <div
            id={`${id}-${rows.length}`}
            role="option"
            aria-selected={false}
            aria-disabled={creating}
            data-active={current === rows.length}
            className={cn(rowClass, creating && "opacity-50")}
            onPointerMove={() => setActive(rows.length)}
            onMouseDown={(event) => event.preventDefault()}
            onClick={() => choose(rows.length)}
          >
            <PlusIcon className="size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
            <span className="truncate">{createText ? createText(name) : name}</span>
          </div>
        ) : null}
        {count === 0 ? <p className="px-2 py-1.5 text-[0.8125rem] leading-5 text-muted-foreground">{emptyText}</p> : null}
      </div>
      {error ? (
        <p role="alert" className="border-t px-3 py-2 text-xs leading-4 text-destructive">
          {error}
        </p>
      ) : null}
    </div>
  );
}
