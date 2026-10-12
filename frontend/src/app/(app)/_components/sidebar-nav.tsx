"use client";

import { useState } from "react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { BoxIcon, ChevronDownIcon, LayoutGridIcon } from "lucide-react";
import { CreateProjectDialog } from "@/features/projects/components/create-project-dialog";
import type { Project } from "@/features/projects/types";
import { cn } from "@/lib/utils";
import { messages } from "@/messages";

const m = messages.projects;

const itemClass =
  "flex h-7 items-center gap-2 rounded-md px-2 text-[0.8125rem] leading-5 outline-none focus-visible:ring-3 focus-visible:ring-ring/50 max-md:h-9";

function NavItem({ href, current, children }: { href: string; current: boolean; children: React.ReactNode }) {
  return (
    <Link
      href={href}
      aria-current={current ? "page" : undefined}
      className={cn(
        itemClass,
        current ? "bg-sidebar-accent font-medium text-sidebar-accent-foreground" : "hover:bg-sidebar-accent",
      )}
    >
      {children}
    </Link>
  );
}

/** The sidebar's navigation: every project you can enter, under a section that can be folded away. */
export function SidebarNav({ projects }: { projects: Project[] }) {
  const pathname = usePathname();
  const [open, setOpen] = useState(true);

  return (
    <nav aria-label={m.title} className="flex min-h-0 flex-1 flex-col gap-4 overflow-y-auto overscroll-contain px-2 py-1">
      <ul className="flex flex-col gap-px">
        <li>
          <NavItem href="/" current={pathname === "/"}>
            <LayoutGridIcon className="size-4 text-muted-foreground" aria-hidden="true" />
            {m.all}
          </NavItem>
        </li>
      </ul>
      <div className="flex flex-col gap-px">
        <div className="flex h-7 items-center justify-between">
          <button
            type="button"
            aria-expanded={open}
            aria-controls="sidebar-projects"
            onClick={() => setOpen((current) => !current)}
            className="flex h-6 items-center gap-1 rounded-md px-2 text-xs font-medium text-muted-foreground outline-none hover:bg-sidebar-accent hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50"
          >
            {m.mine}
            <ChevronDownIcon className={cn("size-3", open ? null : "-rotate-90")} aria-hidden="true" />
          </button>
          <CreateProjectDialog variant="icon" />
        </div>
        {open ? (
          <ul id="sidebar-projects" className="flex flex-col gap-px">
            {projects.length === 0 ? (
              <li className="px-2 py-1 text-xs text-muted-foreground">{m.sidebarEmpty}</li>
            ) : (
              projects.map((project) => {
                const href = `/project/${project.key}`;
                return (
                  <li key={project.id}>
                    <NavItem href={href} current={pathname === href || pathname.startsWith(`${href}/`)}>
                      <BoxIcon className="size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
                      <span className="truncate">{project.name}</span>
                    </NavItem>
                  </li>
                );
              })
            )}
          </ul>
        ) : null}
      </div>
    </nav>
  );
}
