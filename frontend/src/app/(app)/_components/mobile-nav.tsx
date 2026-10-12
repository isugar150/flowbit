"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { CircleDotIcon, CircleUserRoundIcon, LayoutGridIcon, MenuIcon } from "lucide-react";
import { ProjectIcon } from "@/components/project-icon";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import type { ProjectSummary } from "@/features/projects/types";
import { messages } from "@/messages";

const m = messages.projects;

/**
 * The sidebar's destinations on a narrow screen, where the sidebar itself is hidden: a menu
 * button in the top bar with the same places and the projects the person can enter.
 */
export function MobileNav({ projects: allProjects }: { projects: ProjectSummary[] }) {
  const pathname = usePathname();
  const projects = allProjects.filter((project) => project.myRole !== null);
  const current = (href: string, exact = false) =>
    pathname === href || (!exact && pathname.startsWith(`${href}/`)) ? ("page" as const) : undefined;

  return (
    <DropdownMenu>
      <DropdownMenuTrigger render={<Button variant="ghost" size="icon-lg" aria-label={messages.nav.menu} className="size-11" />}>
        <MenuIcon />
      </DropdownMenuTrigger>
      <DropdownMenuContent side="bottom" align="start" className="max-h-[70dvh] w-64">
        <DropdownMenuGroup>
          <DropdownMenuItem className="min-h-11" aria-current={current("/my-issues")} render={<Link href="/my-issues" />}>
            <CircleUserRoundIcon />
            {messages.myIssues.title}
          </DropdownMenuItem>
          <DropdownMenuItem className="min-h-11" aria-current={current("/issues", true)} render={<Link href="/issues" />}>
            <CircleDotIcon />
            {messages.issues.all}
          </DropdownMenuItem>
          <DropdownMenuItem className="min-h-11" aria-current={current("/", true)} render={<Link href="/" />}>
            <LayoutGridIcon />
            {m.all}
          </DropdownMenuItem>
        </DropdownMenuGroup>
        <DropdownMenuSeparator />
        <DropdownMenuGroup>
          <DropdownMenuLabel>{m.mine}</DropdownMenuLabel>
          {projects.length === 0 ? (
            <p className="px-1.5 py-1 text-xs text-muted-foreground">{m.sidebarEmpty}</p>
          ) : (
            projects.map((project) => {
              const href = `/project/${project.id}`;
              return (
                <DropdownMenuItem key={project.id} className="min-h-11" aria-current={current(href)} render={<Link href={href} />}>
                  <ProjectIcon iconUrl={project.iconUrl} />
                  <span className="truncate">{project.name}</span>
                </DropdownMenuItem>
              );
            })
          )}
        </DropdownMenuGroup>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
