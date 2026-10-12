"use client";

import { useId, useState } from "react";
import { ChevronDownIcon } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { cn } from "@/lib/utils";

/**
 * One integration on an administrator page: a row that says what it is and whether it is on, and
 * opens to its settings. Several of these stack into a list that can be read at a glance, instead
 * of every form of the page laid end to end.
 */
export function SettingsCard({
  icon,
  title,
  description,
  status,
  defaultOpen = false,
  children,
}: {
  icon: React.ReactNode;
  title: string;
  description: string;
  /** The badge on the right: "사용 중", "꺼짐", "3개". `on` colours it. */
  status: { label: string; on: boolean };
  defaultOpen?: boolean;
  children: React.ReactNode;
}) {
  const [open, setOpen] = useState(defaultOpen);
  const bodyId = useId();

  return (
    <section className="rounded-xl border bg-card">
      <h3>
        <button
          type="button"
          aria-expanded={open}
          aria-controls={bodyId}
          className={cn(
            "flex w-full items-center gap-3 rounded-xl px-4 py-3 text-left outline-none hover:bg-muted/50 focus-visible:ring-3 focus-visible:ring-ring/50",
            open && "rounded-b-none",
          )}
          onClick={() => setOpen(!open)}
        >
          <span
            aria-hidden="true"
            className="flex size-9 shrink-0 items-center justify-center rounded-lg bg-muted text-muted-foreground [&_svg]:size-4.5"
          >
            {icon}
          </span>
          <span className="flex min-w-0 flex-1 flex-col">
            <span className="text-[0.9375rem] leading-[1.375rem] font-semibold">
              {title}
            </span>
            <span className="text-[0.8125rem] leading-5 font-normal text-muted-foreground">
              {description}
            </span>
          </span>
          <Badge
            variant={status.on ? "default" : "outline"}
            className={status.on ? undefined : "text-muted-foreground"}
          >
            {status.label}
          </Badge>
          <ChevronDownIcon
            aria-hidden="true"
            className={cn(
              "size-4 shrink-0 text-muted-foreground transition-transform",
              open && "rotate-180",
            )}
          />
        </button>
      </h3>
      {/* Kept mounted while closed, so half-typed values survive collapsing the card. */}
      <div id={bodyId} hidden={!open} className="border-t px-4 py-5">
        {children}
      </div>
    </section>
  );
}

/** The row of buttons that ends a settings form, set apart from the fields above it. */
export function SettingsActions({ children }: { children: React.ReactNode }) {
  return (
    <div className="flex flex-wrap items-center gap-2 border-t pt-4">
      {children}
    </div>
  );
}
