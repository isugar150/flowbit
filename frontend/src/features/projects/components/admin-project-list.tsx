"use client";

import { useState } from "react";
import Link from "next/link";
import { SearchIcon } from "lucide-react";
import { ProjectIcon } from "@/components/project-icon";
import { InputGroup, InputGroupAddon, InputGroupInput } from "@/components/ui/input-group";
import { cn } from "@/lib/utils";
import { messages } from "@/messages";
import type { ProjectSummary } from "../types";
import { RestoreProjectButton } from "./restore-project-button";

const m = messages.adminProjects;

/** Where a project's settings are, in 관리자 설정 > 프로젝트: the page itself, with the project in the address. */
function adminProjectAddress(projectId: string): string {
  return `/admin/projects?project=${encodeURIComponent(projectId)}`;
}

/**
 * Every project, to pick the one whose settings to change (D-236): the ones in use, searchable by name, and below
 * them the archived ones, which can only be restored. The projects the signed-in administrator was not added to are
 * in it like the others.
 */
export function AdminProjectList({
  projects,
  archived,
  selectedId,
}: {
  projects: ProjectSummary[];
  archived: ProjectSummary[];
  selectedId: string | null;
}) {
  const [query, setQuery] = useState("");
  const needle = query.trim().toLowerCase();
  const shown = needle ? projects.filter((project) => project.name.toLowerCase().includes(needle)) : projects;

  return (
    // Stays in view while the settings on the right scroll (the page header and the tabs are sticky above it, hence top-24).
    <div className="flex flex-col gap-2 md:sticky md:top-24 md:max-h-[calc(100dvh-8rem)] md:overflow-y-auto md:overscroll-contain">
      <InputGroup className="h-9 max-md:h-11">
        <InputGroupAddon>
          <SearchIcon />
        </InputGroupAddon>
        <InputGroupInput
          type="search"
          aria-label={m.search}
          placeholder={m.search}
          value={query}
          onChange={(event) => setQuery(event.target.value)}
        />
      </InputGroup>
      <p className="px-1 text-[0.8125rem] leading-5 text-muted-foreground">
        {needle ? m.countOf(shown.length, projects.length) : m.count(projects.length)}
      </p>
      {shown.length === 0 ? (
        <p className="rounded-xl border px-3 py-6 text-center text-[0.8125rem] leading-5 text-muted-foreground">
          {projects.length === 0 ? m.empty : m.noMatch}
        </p>
      ) : (
        <ul
          aria-label={m.listLabel}
          className="flex flex-col gap-0.5 rounded-xl border p-1 max-md:max-h-64 max-md:overflow-y-auto"
        >
          {shown.map((project) => {
            const current = project.id === selectedId;
            return (
              <li key={project.id}>
                <Link
                  href={adminProjectAddress(project.id)}
                  scroll={false}
                  aria-current={current ? "true" : undefined}
                  className={cn(
                    "flex w-full items-center gap-2.5 rounded-lg px-2 py-1.5 text-left outline-none hover:bg-muted/60 focus-visible:ring-3 focus-visible:ring-ring/50 max-md:min-h-11",
                    current && "bg-muted hover:bg-muted",
                  )}
                >
                  <ProjectIcon iconUrl={project.iconUrl} />
                  <span className="flex min-w-0 flex-1 flex-col">
                    <span className="truncate text-[0.8125rem] leading-5 font-medium">{project.name}</span>
                    <span className="truncate text-xs leading-4 text-muted-foreground">
                      {messages.projects.memberCount(project.memberCount)}
                      {project.openToAll ? ` · ${messages.projects.openBadge}` : ""}
                    </span>
                  </span>
                </Link>
              </li>
            );
          })}
        </ul>
      )}
      {archived.length > 0 ? (
        <section aria-labelledby="admin-projects-archived" className="mt-3 flex flex-col gap-2">
          <div className="flex flex-col gap-0.5 px-1">
            <h3 id="admin-projects-archived" className="text-[0.8125rem] leading-5 font-medium">
              {m.archivedTitle}
              <span className="ml-2 font-normal text-muted-foreground tabular-nums">{archived.length}</span>
            </h3>
            <p className="text-xs leading-4 text-muted-foreground">{m.archivedNote}</p>
          </div>
          <ul className="flex flex-col divide-y rounded-xl border border-dashed">
            {archived.map((project) => (
              <li key={project.id} className="flex items-center justify-between gap-2 px-3 py-2">
                <span className="flex min-w-0 items-center gap-2 text-muted-foreground">
                  <ProjectIcon iconUrl={project.iconUrl} />
                  <span className="truncate text-[0.8125rem] leading-5">{project.name}</span>
                </span>
                <RestoreProjectButton projectId={project.id} name={project.name} scope="admin" />
              </li>
            ))}
          </ul>
        </section>
      ) : null}
    </div>
  );
}
