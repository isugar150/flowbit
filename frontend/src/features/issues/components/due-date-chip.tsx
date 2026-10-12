"use client";

import { CalendarIcon, XIcon } from "lucide-react";
import { cn } from "@/lib/utils";
import { messages } from "@/messages";
import { chipClass } from "./chip-select";

const m = messages.issues;

/** The due date as a chip: the browser's own date picker, so it works with keyboard and on phones. */
export function DueDateChip({
  value,
  onChange,
  disabled,
}: {
  /** YYYY-MM-DD or null */
  value: string | null;
  onChange: (value: string | null) => void;
  disabled?: boolean;
}) {
  return (
    <span className={cn(chipClass, "max-w-none focus-within:border-ring focus-within:ring-3 focus-within:ring-ring/50")}>
      <CalendarIcon className="size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
      <input
        type="date"
        aria-label={m.dueDate}
        value={value ?? ""}
        disabled={disabled}
        onChange={(event) => onChange(event.target.value || null)}
        className={cn("bg-transparent tabular-nums outline-none", value ? null : "text-muted-foreground")}
      />
      {value && !disabled ? (
        <button
          type="button"
          aria-label={m.clearDueDate}
          onClick={() => onChange(null)}
          className="-mr-1 rounded-full p-0.5 text-muted-foreground outline-none hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50"
        >
          <XIcon className="size-3.5" aria-hidden="true" />
        </button>
      ) : null}
    </span>
  );
}
