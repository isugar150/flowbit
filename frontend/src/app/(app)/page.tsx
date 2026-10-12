import type { Metadata } from "next";
import Link from "next/link";
import { BoxIcon, DiamondIcon, LayoutGridIcon, LockIcon } from "lucide-react";
import { PageHeader } from "@/components/page-header";
import { ProjectIcon } from "@/components/project-icon";
import { PillTabs } from "@/components/pill-tabs";
import { Empty, EmptyContent, EmptyDescription, EmptyHeader, EmptyMedia, EmptyTitle } from "@/components/ui/empty";
import { PriorityIcon, priorityLabel } from "@/features/issues/meta";
import { PersonAvatar } from "@/features/issues/options";
import { milestoneProgress } from "@/features/milestones/types";
import { CreateProjectDialog } from "@/features/projects/components/create-project-dialog";
import { HealthBadge, ProjectStatusIcon, projectStatusLabel } from "@/features/projects/meta";
import type { ProjectSummary } from "@/features/projects/types";
import { getProjects } from "@/lib/current-user";
import { messages } from "@/messages";
import { DueDate, TimeAgo } from "@/components/dates";

const m = messages.projects;

export const metadata: Metadata = { title: m.title };

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
 * All projects as a table, after Linear's project list: name with the current milestone,
 * health from the latest update, priority, lead, target date, issue count, and status with
 * progress. A whole row opens the project. Projects the person has not been added to are listed
 * too, with a lock; opening one says whom to ask (D-047).
 */
export default async function HomePage() {
  // The same list the sidebar loaded for this request; no second call to the backend.
  const projects: ProjectSummary[] = await getProjects();

  return (
    <>
      <PageHeader
        crumbs={[{ label: m.title, icon: <LayoutGridIcon /> }]}
        actions={projects.length > 0 ? <CreateProjectDialog variant="ghost" /> : null}
      />
      {projects.length === 0 ? (
        <div className="flex flex-1 items-center justify-center p-6">
          <Empty>
            <EmptyHeader>
              <EmptyMedia variant="icon">
                <BoxIcon />
              </EmptyMedia>
              <EmptyTitle>{m.emptyTitle}</EmptyTitle>
              <EmptyDescription>{m.emptyDescription}</EmptyDescription>
            </EmptyHeader>
            <EmptyContent>
              <CreateProjectDialog variant="button" />
            </EmptyContent>
          </Empty>
        </div>
      ) : (
        <>
          <PillTabs label={m.listLabel} tabs={[{ href: "/", label: m.all }]} />
          <table className="w-full table-fixed border-collapse">
            <caption className="sr-only">{m.listLabel}</caption>
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
              </tr>
            </thead>
            <tbody>
              {projects.map((project) => (
                <ProjectRow key={project.id} project={project} />
              ))}
            </tbody>
          </table>
        </>
      )}
    </>
  );
}

function ProjectRow({ project }: { project: ProjectSummary }) {
  const percent = milestoneProgress(project);
  const lead = project.lead;
  const locked = project.myRole === null;

  return (
    // The link in the name cell stretches over the whole row, so any part of it opens the project.
    <tr className="relative border-b hover:bg-muted has-[a:focus-visible]:bg-muted">
      <td className={`${cell} pl-5`}>
        <div className="flex min-w-0 items-center gap-3">
          {locked ? (
            <LockIcon className="size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
          ) : (
            <ProjectIcon iconUrl={project.iconUrl} />
          )}
          <Link
            href={locked ? `/no-access/${project.id}` : `/project/${project.id}`}
            title={locked ? m.locked : undefined}
            className="min-w-0 truncate font-medium outline-none after:absolute after:inset-0"
          >
            {project.name}
            {locked ? <span className="sr-only"> ({m.locked})</span> : null}
          </Link>
          {project.currentMilestone ? (
            <span className="flex min-w-0 shrink items-center gap-1 text-muted-foreground">
              <DiamondIcon className="size-3.5 shrink-0 text-warning" aria-hidden="true" />
              <span className="sr-only">{m.currentMilestone}: </span>
              <span className="truncate">{project.currentMilestone}</span>
            </span>
          ) : null}
        </div>
      </td>
      <td className={`${cell} ${widest}`}>
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
      <td className={`${cell} ${wide}`}>
        <span className="flex items-center" title={priorityLabel(project.priority)}>
          <PriorityIcon priority={project.priority} className="text-muted-foreground" />
          <span className="sr-only">{priorityLabel(project.priority)}</span>
        </span>
      </td>
      <td className={`${cell} ${wider}`}>
        {lead ? (
          <span className="flex min-w-0 items-center gap-1.5">
            <PersonAvatar person={lead} />
            <span className="truncate">{lead.displayName ?? lead.loginId}</span>
          </span>
        ) : null}
      </td>
      <td className={`${cell} ${wider} text-muted-foreground tabular-nums`}>
        {project.targetDate ? <DueDate value={project.targetDate} /> : null}
      </td>
      <td className={`${cell} ${wide} text-right font-medium tabular-nums`}>{project.issueCount}</td>
      <td className={`${cell} pr-5`}>
        <span className="flex items-center gap-1.5" title={projectStatusLabel(project.status)}>
          <ProjectStatusIcon status={project.status} />
          <span className="sr-only">{projectStatusLabel(project.status)}, </span>
          <span className="sr-only">{m.progress(percent)}</span>
          <span className="font-medium tabular-nums" aria-hidden="true">
            {percent}%
          </span>
        </span>
      </td>
    </tr>
  );
}
