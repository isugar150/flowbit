"use client";

import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuGroup,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { cn } from "@/lib/utils";

export type ChipOption<T extends string> = {
  value: T;
  label: string;
  icon?: React.ReactNode;
  /** Drawn in grey on the chip: the value that stands for "nothing chosen". */
  muted?: boolean;
  /** A short note in grey under the option in the list (a milestone's purpose). */
  hint?: string;
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
 * A rounded chip that shows one property of an issue and opens a menu to change it.
 * `iconOnly` shows just the icon, for dense list rows; the label is still read by screen readers.
 */
export function ChipSelect<T extends string>({
  label,
  value,
  options,
  onChange,
  disabled,
  iconOnly,
  plain,
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
}) {
  const current = options.find((option) => option.value === value);

  return (
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
            )}
          />
        }
      >
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
                {option.hint ? (
                  <span className="flex min-w-0 flex-col">
                    <span className="truncate">{option.label}</span>
                    <span className="truncate text-xs text-muted-foreground">{option.hint}</span>
                  </span>
                ) : (
                  <span className="truncate">{option.label}</span>
                )}
              </DropdownMenuRadioItem>
            ))}
          </DropdownMenuRadioGroup>
        </DropdownMenuGroup>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
