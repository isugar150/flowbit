"use client";

import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuGroup,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { XIcon } from "lucide-react";
import { cn } from "@/lib/utils";

export type ChipOption<T extends string> = {
  value: T;
  label: string;
  icon?: React.ReactNode;
  /** Drawn in grey on the chip: the value that stands for "nothing chosen". */
  muted?: boolean;
};

export const chipClass =
  "flex h-7 max-w-48 shrink-0 items-center gap-1.5 rounded-full border bg-background px-2.5 text-[0.8125rem] leading-5 outline-none hover:bg-muted focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 disabled:pointer-events-none disabled:opacity-50 aria-expanded:bg-muted max-md:h-9";

/** The same control without the pill: a quiet row that only shows its edge when pointed at. For side panels. */
export const plainChipClass =
  "-mx-2 flex h-8 max-w-full items-center gap-2 rounded-md px-2 text-[0.8125rem] leading-5 font-medium outline-none hover:bg-muted focus-visible:ring-3 focus-visible:ring-ring/50 disabled:pointer-events-none aria-expanded:bg-muted max-md:h-10";

/** For a value in a column of a list line: no frame, the line's muted text, a background when pointed at. */
export const cellChipClass =
  "-mx-1 flex h-7 w-full min-w-0 items-center gap-1.5 rounded-md px-1 text-[0.8125rem] leading-5 text-muted-foreground outline-none hover:bg-background hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50 disabled:pointer-events-none aria-expanded:bg-background max-md:h-9";

/**
 * The trigger of a chip that can be taken off (a filter that is on): the frame is drawn around it and its "x" by
 * `RemovableChip`, so the trigger has none of its own and is a little shorter than the frame.
 */
export const removableChipClass = "h-6.5 max-w-60 rounded-l-full rounded-r-none border-0 pr-1.5 max-md:h-8.5";

/** A chip with an "x" at its end that takes it off; `children` is the chip's trigger, drawn with `removableChipClass`. */
export function RemovableChip({
  onClear,
  clearLabel,
  children,
}: {
  onClear: () => void;
  /** What the "x" does, for screen readers and the tooltip: "상태 필터 지우기". */
  clearLabel: string;
  children: React.ReactNode;
}) {
  return (
    <span className="flex shrink-0 items-center rounded-full border bg-background">
      {children}
      <button
        type="button"
        aria-label={clearLabel}
        title={clearLabel}
        onClick={onClear}
        className="flex h-6.5 w-6 shrink-0 items-center justify-center rounded-r-full text-muted-foreground outline-none hover:bg-muted hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50 max-md:h-8.5 max-md:w-8"
      >
        <XIcon className="size-3.5" aria-hidden="true" />
      </button>
    </span>
  );
}

/**
 * A rounded chip that shows one property of an issue and opens a menu to change it.
 * `iconOnly` shows just the icon, for dense list rows; the label is still read by screen readers.
 * With `onClear` it is a filter that is on: the chip names what it filters (`prefix`) and ends in an "x" that turns it off.
 */
export function ChipSelect<T extends string>({
  label,
  value,
  options,
  onChange,
  disabled,
  iconOnly,
  plain,
  prefix,
  onClear,
  clearLabel,
}: {
  /** What this chip sets, for example "상태". */
  label: string;
  value: T;
  options: ChipOption<T>[];
  onChange: (value: T) => void;
  disabled?: boolean;
  iconOnly?: boolean;
  /** Draw it as a plain row (icon and value) instead of a pill. */
  plain?: boolean;
  /** Said before the value, in grey: "상태" in "상태 진행 중". */
  prefix?: string;
  /** Puts an "x" at the end of the chip that calls this. */
  onClear?: () => void;
  clearLabel?: string;
}) {
  const current = options.find((option) => option.value === value);

  const menu = (
    <DropdownMenu>
      <DropdownMenuTrigger
        render={
          <button
            type="button"
            disabled={disabled}
            aria-label={`${label}: ${current?.label ?? ""}`}
            className={cn(
              plain ? plainChipClass : chipClass,
              iconOnly && "size-7 justify-center border-transparent bg-transparent px-0 max-md:size-9",
              onClear && removableChipClass,
            )}
          />
        }
      >
        {prefix ? <span className="shrink-0 text-muted-foreground">{prefix}</span> : null}
        {current?.icon}
        {iconOnly ? null : (
          <span className={cn("truncate", current?.muted && "font-normal text-muted-foreground")}>{current?.label}</span>
        )}
      </DropdownMenuTrigger>
      <DropdownMenuContent className="w-52">
        <DropdownMenuGroup>
          <DropdownMenuRadioGroup value={value} onValueChange={(next) => onChange(next as T)}>
            {options.map((option) => (
              // Choosing is the whole point of opening the menu, so it closes (radio items stay open by default).
              <DropdownMenuRadioItem key={option.value} value={option.value} closeOnClick>
                {option.icon}
                <span className="truncate">{option.label}</span>
              </DropdownMenuRadioItem>
            ))}
          </DropdownMenuRadioGroup>
        </DropdownMenuGroup>
      </DropdownMenuContent>
    </DropdownMenu>
  );
  return onClear ? (
    <RemovableChip onClear={onClear} clearLabel={clearLabel ?? label}>
      {menu}
    </RemovableChip>
  ) : (
    menu
  );
}
