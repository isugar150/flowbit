"use client";

import { useState, type ReactNode } from "react";
import { Input } from "@/components/ui/input";
import { DropdownMenuCheckboxItem, DropdownMenuSub, DropdownMenuSubContent, DropdownMenuSubTrigger } from "@/components/ui/dropdown-menu";

export function Choices({ label, icon, children, disabled, value }: { label: string; icon: ReactNode; children: ReactNode; disabled?: boolean; value?: string }) {
  return (
    <DropdownMenuSub>
      <DropdownMenuSubTrigger disabled={disabled}>
        {icon}<span className="min-w-0 truncate">{label}</span>
        {value ? <span className="ml-auto max-w-20 truncate text-xs text-muted-foreground">{value}</span> : null}
      </DropdownMenuSubTrigger>
      <DropdownMenuSubContent className="max-h-80 w-64">{children}</DropdownMenuSubContent>
    </DropdownMenuSub>
  );
}

export function SearchChoices({ label, icon, placeholder, disabled, children }: { label: string; icon: ReactNode; placeholder: string; disabled?: boolean; children: (query: string) => ReactNode }) {
  const [query, setQuery] = useState("");
  return (
    <DropdownMenuSub onOpenChange={(open) => { if (!open) setQuery(""); }}>
      <DropdownMenuSubTrigger disabled={disabled}>{icon}{label}</DropdownMenuSubTrigger>
      <DropdownMenuSubContent className="max-h-80 w-72">
        <div className="sticky top-0 z-10 bg-popover pb-1">
          <Input
            aria-label={placeholder}
            placeholder={placeholder}
            value={query}
            autoFocus
            autoComplete="off"
            onChange={(event) => setQuery(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === "Escape" || event.key === "Tab") return;
              // Search typing must not become the parent menu's typeahead or select an item on Space.
              event.stopPropagation();
              if (event.key === "ArrowDown" && !event.nativeEvent.isComposing) {
                event.preventDefault();
                event.currentTarget.closest('[role="menu"]')?.querySelector<HTMLElement>('[role="menuitemcheckbox"]:not([aria-disabled="true"])')?.focus();
              }
            }}
          />
        </div>
        {children(query.trim().toLocaleLowerCase())}
      </DropdownMenuSubContent>
    </DropdownMenuSub>
  );
}

export function Choice({ checked, children, onClick, disabled }: { checked: boolean; children: ReactNode; onClick: () => void; disabled?: boolean }) {
  return <DropdownMenuCheckboxItem checked={checked} disabled={disabled} closeOnClick onCheckedChange={onClick}>{children}</DropdownMenuCheckboxItem>;
}
