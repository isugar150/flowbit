"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { cn } from "@/lib/utils";
import { messages } from "@/messages";

const m = messages.settings;

/** Tabs across the top of the settings pages. The workspace tab exists only for administrators. */
export function SettingsNav({ isAdmin }: { isAdmin: boolean }) {
  const pathname = usePathname();
  const items = [
    { href: "/settings/profile", label: m.profileTab },
    ...(isAdmin ? [{ href: "/settings/workspace", label: m.workspaceTab }] : []),
  ];

  return (
    <nav aria-label={m.navLabel} className="flex gap-1 border-b">
      {items.map((item) => {
        const current = pathname === item.href;
        return (
          <Link
            key={item.href}
            href={item.href}
            aria-current={current ? "page" : undefined}
            className={cn(
              "-mb-px flex h-9 items-center border-b-2 px-3 outline-none focus-visible:ring-3 focus-visible:ring-ring/50 max-md:h-11",
              current
                ? "border-primary font-medium text-foreground"
                : "border-transparent text-muted-foreground hover:text-foreground",
            )}
          >
            {item.label}
          </Link>
        );
      })}
    </nav>
  );
}
