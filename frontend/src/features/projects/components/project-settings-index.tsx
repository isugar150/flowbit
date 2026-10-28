"use client";

import { useEffect, useRef, useState, type ReactNode } from "react";
import { cn } from "@/lib/utils";
import { messages } from "@/messages";

export type ProjectSettingsEntry = { id: string; title: string; content: ReactNode };

/** Keep navigation local: Next may retain a hidden settings page for another project. */
export function ProjectSettingsIndex({ sections, projectId }: { sections: ProjectSettingsEntry[]; projectId: string }) {
  const root = useRef<HTMLDivElement>(null);
  const [active, setActive] = useState(sections[0]?.id ?? "");
  const keys = sections.map((section) => section.id).join("|");
  useEffect(() => {
    const targets = root.current?.querySelectorAll<HTMLElement>("[data-settings-section]");
    if (!targets) return;
    const observer = new IntersectionObserver((entries) => {
      const visible = entries.filter((entry) => entry.isIntersecting).sort((a, b) => a.boundingClientRect.top - b.boundingClientRect.top);
      const key = visible[0]?.target.getAttribute("data-settings-section");
      if (key) setActive(key);
    }, { rootMargin: "-100px 0px -55% 0px", threshold: 0 });
    targets.forEach((target) => observer.observe(target));
    return () => observer.disconnect();
  }, [keys]);
  return <div ref={root} className="grid min-w-0 items-start gap-6 lg:grid-cols-[10rem_minmax(0,1fr)]">
    <nav aria-label={messages.projectFields.index} className="flex flex-wrap gap-1 rounded-lg border p-2 lg:sticky lg:top-24 lg:max-h-[calc(100dvh-8rem)] lg:flex-col lg:overflow-y-auto">
      {sections.map((section) => <a key={section.id} href={`#project-${projectId}-${section.id}`} aria-current={active === section.id ? "location" : undefined} className={cn("rounded-md px-3 py-2 text-sm outline-none hover:bg-muted focus-visible:ring-3 focus-visible:ring-ring/50", active === section.id ? "bg-muted font-medium" : "text-muted-foreground")} onClick={(event) => {
        event.preventDefault();
        const target = root.current?.querySelector<HTMLElement>(`[data-settings-section="${section.id}"]`);
        target?.scrollIntoView({ behavior: window.matchMedia("(prefers-reduced-motion: reduce)").matches ? "instant" : "smooth", block: "start" });
        target?.focus({ preventScroll: true });
        setActive(section.id);
      }}>{section.title}</a>)}
    </nav>
    <div className="flex min-w-0 flex-col gap-10">{sections.map((section) => <div key={section.id} id={`project-${projectId}-${section.id}`} data-settings-section={section.id} tabIndex={-1} className="min-w-0 scroll-mt-28 outline-none">{section.content}</div>)}</div>
  </div>;
}
