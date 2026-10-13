import Link from "next/link";
import { ArchiveIcon, DiamondIcon, LockIcon } from "lucide-react";
import { DueDate, TimeAgo } from "@/components/dates";
import { PersonAvatar, personName } from "@/components/person";
import { ProjectIcon } from "@/components/project-icon";
import { PriorityIcon, priorityLabel } from "@/features/issues/meta";
import { milestoneProgress } from "@/features/milestones/types";
import { cn } from "@/lib/utils";
import { messages } from "@/messages";
import { HealthBadge, ProjectStatusIcon, projectStatusLabel } from "../meta";
import type { ProjectSummary } from "../types";
import { RestoreProjectButton } from "./restore-project-button";

const m = messages.projects;

const headCell = "h-9 px-2 text-left text-[0.8125rem] leading-5 font-normal text-muted-foreground";
const cell = "h-11 px-2 text-[0.8125rem] leading-5 max-md:h-14";
/**
 * Columns that only fit on wider screens; the name and the status always show. The name takes
 * what is left, so the widest columns wait for the widest screens rather than squeeze it.
 */
const wide = "max-md:hidden";
const wider = "max-lg:hidden";
const widest = "max-xl:hidden";

/**
 * Projects as a table, after Linear's project list: name with the current milestone, health from
 * the latest update, priority, lead, target date, issue count, and status with progress. A whole
 * row opens the project. Projects the person has not been added to are listed too, with a lock;
 * opening one says whom to ask (D-047).
 *
 * `archived` draws the projects that were put away: greyed out, not links (an archived project
 * cannot be entered), with "복원" for those who manage them.
 */
export function ProjectTable({ projects, archived = false }: { projects: ProjectSummary[]; archived?: boolean }) {
  return (
    <table className="w-full table-fixed border-collapse">
      <caption className="sr-only">{archived ? m.archivedListLabel : m.listLabel}</caption>
      <thead>
        <tr className="border-b">
          <th scope="col" className={`${headCell} pl-11`}>
            {m.colName}
          </th>
          <th scope="col" className={`${headCell} ${widest} w-44`}>
            {m.propHealth}
          </th>
          <th scope="col" className={`${headCell} ${wide} w-24`}>
            {m.propPriority}
          </th>
          <th scope="col" className={`${headCell} ${wider} w-36`}>
            {m.propLead}
          </th>
          <th scope="col" className={`${headCell} ${wider} w-28`}>
            {m.colTargetDate}
          </th>
          <th scope="col" className={`${headCell} ${wide} w-16 text-right`}>
            {m.colIssues}
          </th>
          <th scope="col" className={`${headCell} w-24 pr-5`}>
            {m.propStatus}
          </th>
          {archived ? (
            <th scope="col" className={`${headCell} w-24 pr-5`}>
              <span className="sr-only">{m.restore}</span>
            </th>
          ) : null}
        </tr>
      </thead>
      <tbody>
        {projects.map((project) => (
          <ProjectRow key={project.id} project={project} archived={archived} />
        ))}
      </tbody>
    </table>
  );
}

function ProjectRow({ project, archived }: { project: ProjectSummary; archived: boolean }) {
  const percent = milestoneProgress(project);
  const lead = project.lead;
  const locked = project.myRole === null;
  // An archived line is all grey; what it says is still there to read.
  const faded = archived ? "opacity-60" : null;

  return (
    // The link in the name cell stretches over the whole row, so any part of it opens the project.
    <tr className={cn("relative border-b", archived ? "bg-muted/40 text-muted-foreground" : "hover:bg-muted has-[a:focus-visible]:bg-muted")}>
      <td className={`${cell} pl-5`}>
        <div className={cn("flex min-w-0 items-center gap-3", faded)}>
          {archived ? (
            <ArchiveIcon className="size-4 shrink-0" aria-hidden="true" />
          ) : locked ? (
            <LockIcon className="size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
          ) : (
            <ProjectIcon iconUrl={project.iconUrl} />
          )}
          {archived ? (
            <span className="min-w-0 truncate font-medium">{project.name}</span>
          ) : (
            <Link
              href={locked ? `/no-access/${project.id}` : `/project/${project.id}`}
              title={locked ? m.locked : undefined}
              className="min-w-0 truncate font-medium outline-none after:absolute after:inset-0"
            >
              {project.name}
              {locked ? <span className="sr-only"> ({m.locked})</span> : null}
            </Link>
          )}
          {project.currentMilestone ? (
            <span className="flex min-w-0 shrink items-center gap-1 text-muted-foreground">
              <DiamondIcon className={cn("size-3.5 shrink-0", archived ? null : "text-warning")} aria-hidden="true" />
              <span className="sr-only">{m.currentMilestone}: </span>
              <span className="truncate">{project.currentMilestone}</span>
            </span>
          ) : null}
        </div>
      </td>
      <td className={cn(cell, widest, faded)}>
        {project.health && project.healthUpdatedAt ? (
          <span className="flex min-w-0 items-center gap-1">
            <HealthBadge health={project.health} />
            <time dateTime={project.healthUpdatedAt} className="truncate text-muted-foreground">
              · <TimeAgo iso={project.healthUpdatedAt} />
            </time>
          </span>
        ) : (
          <span className="text-muted-foreground">{m.noUpdates}</span>
        )}
      </td>
      <td className={cn(cell, wide, faded)}>
        <span className="flex items-center" title={priorityLabel(project.priority)}>
          <PriorityIcon priority={project.priority} className="text-muted-foreground" />
          <span className="sr-only">{priorityLabel(project.priority)}</span>
        </span>
      </td>
      <td className={cn(cell, wider, faded)}>
        {lead ? (
          <span className="flex min-w-0 items-center gap-1.5">
            <PersonAvatar person={lead} />
            <span className="truncate">{personName(lead)}</span>
          </span>
        ) : null}
      </td>
      <td className={cn(cell, wider, faded, "text-muted-foreground tabular-nums")}>
        {project.targetDate ? <DueDate value={project.targetDate} /> : null}
      </td>
      <td className={cn(cell, wide, faded, "text-right font-medium tabular-nums")}>{project.issueCount}</td>
      <td className={cn(cell, faded, "pr-5")}>
        <span className="flex items-center gap-1.5" title={projectStatusLabel(project.status)}>
          <ProjectStatusIcon status={project.status} />
          <span className="sr-only">{projectStatusLabel(project.status)}, </span>
          <span className="sr-only">{m.progress(percent)}</span>
          <span className="font-medium tabular-nums" aria-hidden="true">
            {percent}%
          </span>
        </span>
      </td>
      {archived ? (
        <td className={`${cell} pr-5 text-right`}>
          {project.myRole === "MANAGER" ? <RestoreProjectButton projectId={project.id} name={project.name} /> : null}
        </td>
      ) : null}
    </tr>
  );
}
