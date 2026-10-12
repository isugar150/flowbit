"use client";

import { CalendarIcon, XIcon } from "lucide-react";
import { cn } from "@/lib/utils";
import { messages } from "@/messages";
import { chipClass } from "./chip-select";

const m = messages.issues;

/**
 * A date as a chip: the browser's own date picker, so it works with keyboard and on phones.
 * The due date of an issue by default; `label` and `clearLabel` name any other date.
 */
export function DueDateChip({
  value,
  onChange,
  disabled,
  label = m.dueDate,
  clearLabel = m.clearDueDate,
  min,
  max,
}: {
  /** YYYY-MM-DD or null */
  value: string | null;
  onChange: (value: string | null) => void;
  disabled?: boolean;
  label?: string;
  clearLabel?: string;
  /** YYYY-MM-DD bounds offered by the picker */
  min?: string;
  max?: string;
}) {
  return (
    <span className={cn(chipClass, "max-w-none focus-within:border-ring focus-within:ring-3 focus-within:ring-ring/50")}>
      <CalendarIcon className="size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
      <input
        type="date"
        aria-label={label}
        value={value ?? ""}
        min={min}
        max={max}
        disabled={disabled}
        onChange={(event) => onChange(event.target.value || null)}
        className={cn("bg-transparent tabular-nums outline-none", value ? null : "text-muted-foreground")}
      />
      {value && !disabled ? (
        <button
          type="button"
          aria-label={clearLabel}
          onClick={() => onChange(null)}
          className="-mr-1 rounded-full p-0.5 text-muted-foreground outline-none hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50"
        >
          <XIcon className="size-3.5" aria-hidden="true" />
        </button>
      ) : null}
    </span>
  );
}
