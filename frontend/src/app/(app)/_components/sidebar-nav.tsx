"use client";

import { useState } from "react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { ChevronDownIcon } from "lucide-react";
import { ProjectIcon } from "@/components/project-icon";
import { CreateProjectDialog } from "@/features/projects/components/create-project-dialog";
import type { ProjectSummary } from "@/features/projects/types";
import { cn } from "@/lib/utils";
import { messages } from "@/messages";
import { NAV_DESTINATIONS, enterableProjects, isCurrentPath } from "./nav-destinations";

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
export function SidebarNav({ projects: allProjects }: { projects: ProjectSummary[] }) {
  const pathname = usePathname();
  const projects = enterableProjects(allProjects);
  const [open, setOpen] = useState(true);

  return (
    <nav aria-label={m.title} className="flex min-h-0 flex-1 flex-col gap-4 overflow-y-auto overscroll-contain px-2 py-1">
      <ul data-tour="nav" className="flex flex-col gap-px">
        {NAV_DESTINATIONS.map(({ href, label, icon: Icon, exact }) => (
          <li key={href}>
            <NavItem href={href} current={isCurrentPath(pathname, href, exact)}>
              <Icon className="size-4 text-muted-foreground" aria-hidden="true" />
              {label}
            </NavItem>
          </li>
        ))}
      </ul>
      <div data-tour="projects" className="flex flex-col gap-px">
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
                const href = `/project/${project.id}`;
                return (
                  <li key={project.id}>
                    <NavItem href={href} current={isCurrentPath(pathname, href)}>
                      <ProjectIcon iconUrl={project.iconUrl} />
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
