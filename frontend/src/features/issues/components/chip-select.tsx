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
};

export const chipClass =
  "flex h-7 max-w-48 shrink-0 items-center gap-1.5 rounded-full border bg-background px-2.5 text-[0.8125rem] leading-5 outline-none hover:bg-muted focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 disabled:pointer-events-none disabled:opacity-50 aria-expanded:bg-muted max-md:h-9";

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
}: {
  /** What this chip sets, for example "상태". */
  label: string;
  value: T;
  options: ChipOption<T>[];
  onChange: (value: T) => void;
  disabled?: boolean;
  iconOnly?: boolean;
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
            className={cn(chipClass, iconOnly && "size-7 justify-center border-transparent bg-transparent px-0 max-md:size-9")}
          />
        }
      >
        {current?.icon}
        {iconOnly ? null : <span className="truncate">{current?.label}</span>}
      </DropdownMenuTrigger>
      <DropdownMenuContent className="w-52">
        <DropdownMenuGroup>
          <DropdownMenuRadioGroup value={value} onValueChange={(next) => onChange(next as T)}>
            {options.map((option) => (
              <DropdownMenuRadioItem key={option.value} value={option.value}>
                {option.icon}
                <span className="truncate">{option.label}</span>
              </DropdownMenuRadioItem>
            ))}
          </DropdownMenuRadioGroup>
        </DropdownMenuGroup>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
