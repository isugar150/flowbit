"use client";

import { useState } from "react";
import dynamic from "next/dynamic";
import type { DateRange } from "react-day-picker";
import { ArrowRightIcon, CalendarIcon } from "lucide-react";
import type { Calendar as CalendarComponent } from "@/components/ui/calendar";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { cn } from "@/lib/utils";
import { messages } from "@/messages";
import { chipClass, plainChipClass } from "./chip-select";
import { DueDate } from "@/components/dates";

const m = messages.dates;

// The calendar (react-day-picker) is only needed once a date chip is opened, so it is not part of
// the script of every page that shows a date. The box holds the calendar's place while it arrives.
const Calendar = dynamic<React.ComponentProps<typeof CalendarComponent>>(
  () => import("@/components/ui/calendar").then((module) => module.Calendar),
  { ssr: false, loading: () => <div className="h-70 w-58" aria-hidden="true" /> },
);

/** "2026-10-24" ↔ a Date at local midnight. Dates carry no time zone, so neither side shifts a day. */
function toDate(value: string | null): Date | undefined {
  if (!value) return undefined;
  const [year, month, day] = value.split("-").map(Number);
  return new Date(year, month - 1, day);
}

function toValue(date: Date | undefined): string | null {
  if (!date) return null;
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

const clearClass =
  "rounded-md px-2 py-1 text-[0.8125rem] leading-5 text-muted-foreground outline-none hover:bg-muted hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50";

/** One date as a chip that opens a calendar. Picking a day saves it and closes the calendar. */
export function DateChip({
  value,
  onChange,
  label,
  placeholder,
  disabled,
  plain,
}: {
  /** YYYY-MM-DD or null */
  value: string | null;
  onChange: (value: string | null) => void;
  /** What the date is, for example "기한". */
  label: string;
  /** Shown when there is no date. */
  placeholder: string;
  disabled?: boolean;
  /** Draw it as a plain row instead of a pill. */
  plain?: boolean;
}) {
  const [open, setOpen] = useState(false);
  const selected = toDate(value);
  const pick = (next: string | null) => {
    setOpen(false);
    if (next !== value) onChange(next);
  };

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger
        render={
          <button
            type="button"
            disabled={disabled}
            aria-label={`${label}: ${value ?? placeholder}`}
            className={plain ? plainChipClass : chipClass}
          />
        }
      >
        <CalendarIcon className="size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
        <span className={cn("truncate tabular-nums", value ? null : "text-muted-foreground")}>
          {value ? <DueDate value={value} /> : placeholder}
        </span>
      </PopoverTrigger>
      <PopoverContent className="w-auto">
        <Calendar
          mode="single"
          selected={selected}
          defaultMonth={selected}
          // The clicked day, so clicking the chosen day again keeps it instead of clearing it.
          onSelect={(_, day) => pick(toValue(day))}
          autoFocus
        />
        {value ? (
          <div className="flex justify-end border-t pt-1.5">
            <button type="button" className={clearClass} onClick={() => pick(null)}>
              {m.clear}
            </button>
          </div>
        ) : null}
      </PopoverContent>
    </Popover>
  );
}

/**
 * A start and an end date as one chip. In the calendar the first click is the start and the
 * second the end; then it saves and closes. Closing after only the first click keeps just a start.
 */
export function DateRangeChip({
  start,
  end,
  onChange,
  label,
  placeholder,
  disabled,
  singleIsEnd,
  plain,
}: {
  /** YYYY-MM-DD or null */
  start: string | null;
  /** YYYY-MM-DD or null, never before start */
  end: string | null;
  onChange: (range: { start: string | null; end: string | null }) => void;
  label: string;
  placeholder: string;
  disabled?: boolean;
  /**
   * For a date that may be one day or a span (an issue's): picking one day and closing, or the
   * same day twice, gives that day as the end with no start. Without it, one day is a start.
   */
  singleIsEnd?: boolean;
  /** Draw it as a plain row instead of a pill. */
  plain?: boolean;
}) {
  const [open, setOpen] = useState(false);
  // The range being picked; null until the first click, so the saved range stays highlighted.
  const [draft, setDraft] = useState<DateRange | null>(null);
  const saved: DateRange | undefined = start || end ? { from: toDate(start), to: toDate(end) } : undefined;
  const shown = draft ?? saved;

  const save = (picked: { start: string | null; end: string | null }) => {
    const single = singleIsEnd && (picked.end === null || picked.start === picked.end);
    const next = single ? { start: null, end: picked.end ?? picked.start } : picked;
    if (next.start !== start || next.end !== end) onChange(next);
  };

  const close = () => {
    if (draft?.from) save({ start: toValue(draft.from), end: toValue(draft.to) });
    setDraft(null);
    setOpen(false);
  };

  const pickDay = (day: Date) => {
    if (!draft?.from || day < draft.from) {
      setDraft({ from: day, to: undefined });
      return;
    }
    save({ start: toValue(draft.from), end: toValue(day) });
    setDraft(null);
    setOpen(false);
  };

  const text =
    singleIsEnd && end && !start ? (
      <span className="tabular-nums">
        <DueDate value={end} />
      </span>
    ) : start || end ? (
      <>
        <span className="tabular-nums">{start ? <DueDate value={start} /> : "…"}</span>
        <ArrowRightIcon className="size-3 shrink-0 text-muted-foreground" aria-hidden="true" />
        <span className="tabular-nums">{end ? <DueDate value={end} /> : "…"}</span>
      </>
    ) : (
      <span className="text-muted-foreground">{placeholder}</span>
    );

  return (
    <Popover open={open} onOpenChange={(next) => (next ? setOpen(true) : close())}>
      <PopoverTrigger
        render={
          <button
            type="button"
            disabled={disabled}
            aria-label={`${label}: ${start || end ? `${start ?? ""} ~ ${end ?? ""}` : placeholder}`}
            className={cn(plain ? plainChipClass : chipClass, "max-w-none")}
          />
        }
      >
        <CalendarIcon className="size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
        {text}
      </PopoverTrigger>
      <PopoverContent className="w-auto">
        <p className="px-2 pb-1 text-[0.8125rem] leading-5 text-muted-foreground" aria-live="polite">
          {draft?.from ? (singleIsEnd ? m.pickEndOrClose : m.pickEnd) : m.pickStart}
        </p>
        <Calendar
          mode="range"
          selected={shown}
          defaultMonth={shown?.from ?? shown?.to}
          // Clicks are handled by pickDay so that every pick starts from a clear rule:
          // first click = start, second click = end.
          onSelect={(_, day) => pickDay(day)}
          autoFocus
        />
        {start || end ? (
          <div className="flex justify-end border-t pt-1.5">
            <button
              type="button"
              className={clearClass}
              onClick={() => {
                setDraft(null);
                setOpen(false);
                save({ start: null, end: null });
              }}
            >
              {m.clear}
            </button>
          </div>
        ) : null}
      </PopoverContent>
    </Popover>
  );
}
