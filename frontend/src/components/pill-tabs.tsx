"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { cn } from "@/lib/utils";

export type PillTab = {
  href: string;
  label: string;
};

/** The row of rounded tabs under the page header that switches between views of one thing. */
export function PillTabs({ label, tabs }: { label: string; tabs: PillTab[] }) {
  const pathname = usePathname();

  return (
    <nav aria-label={label} className="flex shrink-0 items-center gap-1.5 overflow-x-auto px-4 pb-2">
      {tabs.map((tab) => {
        const current = pathname === tab.href;
        return (
          <Link
            key={tab.href}
            href={tab.href}
            aria-current={current ? "page" : undefined}
            className={cn(
              "flex h-7 shrink-0 items-center rounded-full border px-3 text-[0.8125rem] leading-5 outline-none focus-visible:ring-3 focus-visible:ring-ring/50 max-md:h-9",
              current
                ? "border-transparent bg-secondary font-medium text-foreground"
                : "text-muted-foreground hover:bg-muted hover:text-foreground",
            )}
          >
            {tab.label}
          </Link>
        );
      })}
    </nav>
  );
}
