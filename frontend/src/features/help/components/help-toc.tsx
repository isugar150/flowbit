"use client";

import { useEffect, useState } from "react";
import { cn } from "@/lib/utils";
import { messages } from "@/messages";

export type TocGroup = { id: string; title: string; sections: { id: string; title: string }[] };

/**
 * The help page's table of contents. Beside the text on a wide screen, where it stays in place
 * and marks the section being read; above the text on a narrow one. Plain links, so it works
 * before the script arrives.
 */
export function HelpToc({ groups }: { groups: TocGroup[] }) {
  const [current, setCurrent] = useState<string | null>(null);
  const ids = groups.flatMap((group) => group.sections.map((section) => section.id));
  const idList = ids.join(" ");

  useEffect(() => {
    const order = idList.split(" ");
    const visible = new Set<string>();
    // The band under the sticky page header, down to 40% of the window: a section counts as being
    // read while its top part is in it.
    const observer = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          if (entry.isIntersecting) visible.add(entry.target.id);
          else visible.delete(entry.target.id);
        }
        const first = order.find((id) => visible.has(id));
        if (first) setCurrent(first);
      },
      { rootMargin: "-56px 0px -60% 0px" },
    );
    for (const id of order) {
      const element = document.getElementById(id);
      if (element) observer.observe(element);
    }
    // A link to a section (/help#shortcuts): the page arrives after the app's frame, too late for
    // the browser's own jump to it.
    const target = decodeURIComponent(window.location.hash.slice(1));
    if (target) document.getElementById(target)?.scrollIntoView();
    return () => observer.disconnect();
  }, [idList]);

  return (
    <nav aria-label={messages.help.tocLabel} className="flex flex-col gap-5 text-[0.8125rem] leading-5">
      {groups.map((group) => (
        <div key={group.id} className="flex flex-col gap-2 lg:gap-1">
          <a
            href={`#${group.id}`}
            className="rounded-md px-2 py-1 text-xs font-medium text-muted-foreground outline-none hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50"
          >
            {group.title}
          </a>
          <ul className="flex flex-wrap gap-1.5 lg:flex-col lg:flex-nowrap lg:gap-px">
            {group.sections.map((section) => (
              <li key={section.id}>
                <a
                  href={`#${section.id}`}
                  aria-current={current === section.id ? "location" : undefined}
                  className={cn(
                    "flex items-center rounded-md px-2 py-1 text-muted-foreground outline-none hover:bg-accent hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50 max-lg:h-9 max-lg:rounded-full max-lg:border max-lg:px-3 lg:min-h-7",
                    current === section.id && "bg-accent font-medium text-foreground",
                  )}
                >
                  {section.title}
                </a>
              </li>
            ))}
          </ul>
        </div>
      ))}
    </nav>
  );
}
