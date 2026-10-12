import { notFound } from "next/navigation";
import { CalendarIcon, ShieldIcon, UsersIcon } from "lucide-react";
import { Person } from "@/components/person";
import { ProjectIcon } from "@/components/project-icon";
import { PropertyCard, PropertyRow } from "@/components/property-card";
import type { IssueDocumentSummary } from "@/features/issues/types";
import type { UserSummary } from "@/features/members/types";
import { MilestonesSection } from "@/features/milestones/components/milestones-section";
import type { Milestone } from "@/features/milestones/types";
import { AddProjectMemberButton } from "@/features/projects/components/add-project-member-button";
import { ProjectOverviewEditor } from "@/features/projects/components/project-overview-editor";
import { ProjectProperties } from "@/features/projects/components/project-properties";
import { ProjectSubscribeButton } from "@/features/projects/components/project-subscribe-button";
import { ProjectUpdates } from "@/features/projects/components/project-updates";
import { HealthBadge } from "@/features/projects/meta";
import type { ProjectMember, ProjectUpdate } from "@/features/projects/types";
import { getProjects, loadProject, requireUser, serverGet } from "@/lib/current-user";
import { messages } from "@/messages";

const m = messages.projects;

/** YYYY-MM-DD in the reader's time zone (docs/01-product.md section 7). */
function formatDate(iso: string, timeZone: string): string {
  return new Intl.DateTimeFormat("sv-SE", { timeZone }).format(new Date(iso));
}

/**
 * Project overview: what the project is in the middle, its facts and people in cards on the
 * right. On narrow screens the cards move below the description.
 */
export default async function ProjectOverviewPage({ params }: PageProps<"/project/[id]">) {
  const { id } = await params;
  const path = `/api/v1/projects/${encodeURIComponent(id)}`;
  const [user, project, members, projects, milestones, updates, documents] = await Promise.all([
    requireUser(),
    loadProject(id),
    serverGet<ProjectMember[]>(`${path}/members`),
    getProjects(),
    serverGet<Milestone[]>(`${path}/milestones`),
    serverGet<ProjectUpdate[]>(`${path}/updates`),
    serverGet<IssueDocumentSummary[]>(`${path}/documents`),
  ]);
  if (!members || !milestones || !updates) notFound();
  // People who manage the project can add members right from the card; they need the list of everyone.
  const canManage = project.myRole === "MANAGER";
  const users = canManage ? ((await serverGet<UserSummary[]>("/api/v1/users")) ?? []) : [];
  const candidates = users.filter((person) => !members.some((member) => member.userId === person.id));
  const labelSuggestions = [...new Set(projects.flatMap((other) => other.labels))].sort((a, b) => a.localeCompare(b, "ko"));
  const role = messages.roles[project.myRole];
  const created = formatDate(project.createdAt, user.timezone);

  return (
    <div className="flex flex-1 flex-col gap-8 px-4 pt-2 pb-10 lg:flex-row lg:items-start lg:gap-6">
      <div className="mx-auto flex w-full max-w-3xl min-w-0 flex-1 flex-col gap-6 px-2 pt-8 sm:px-6 lg:pt-14">
        <div className="flex flex-col gap-3">
          <ProjectIcon iconUrl={project.iconUrl} size="lg" />
          <h2 className="text-2xl leading-8 font-semibold">{project.name}</h2>
        </div>
        <ProjectOverviewEditor project={project} documents={documents ?? []} canEdit={project.myRole !== "VIEWER"}>
        <dl className="flex flex-wrap items-center gap-x-5 gap-y-2 text-[0.8125rem] leading-5">
          <div className="flex items-center gap-1.5">
            <ShieldIcon className="size-4 text-muted-foreground" aria-hidden="true" />
            <dt className="sr-only">{m.propRole}</dt>
            <dd className="font-medium">{role}</dd>
          </div>
          <div className="flex items-center gap-1.5">
            <UsersIcon className="size-4 text-muted-foreground" aria-hidden="true" />
            <dt className="sr-only">{m.propMembers}</dt>
            <dd className="font-medium">{m.memberCount(project.memberCount)}</dd>
          </div>
          <div className="flex items-center gap-1.5">
            <CalendarIcon className="size-4 text-muted-foreground" aria-hidden="true" />
            <dt className="sr-only">{m.propCreated}</dt>
            <dd className="font-medium tabular-nums">{created}</dd>
          </div>
        </dl>
        </ProjectOverviewEditor>
        <MilestonesSection projectId={project.id} milestones={milestones} canEdit={project.myRole !== "VIEWER"} />
        <ProjectUpdates
          projectId={project.id}
          updates={updates}
          canEdit={project.myRole !== "VIEWER"}
          canManage={canManage}
          currentUserId={user.id}
        />
      </div>
      <aside className="mx-auto flex w-full max-w-3xl shrink-0 flex-col gap-2 lg:mx-0 lg:w-80">
        <PropertyCard
          title={m.properties}
          action={<ProjectSubscribeButton projectId={project.id} subscribed={project.subscribed} />}
        >
          <dl className="flex flex-col">
            <ProjectProperties
              project={project}
              members={members}
              labelSuggestions={labelSuggestions}
              canEdit={project.myRole !== "VIEWER"}
            />
            <PropertyRow label={m.propHealth}>
              {project.health ? (
                <HealthBadge health={project.health} />
              ) : (
                <span className="font-normal text-muted-foreground">{m.noUpdates}</span>
              )}
            </PropertyRow>
            <PropertyRow label={m.propRole}>{role}</PropertyRow>
            <PropertyRow label={m.propMembers}>{m.memberCount(project.memberCount)}</PropertyRow>
            <PropertyRow label={m.propCreated}>
              <span className="tabular-nums">{created}</span>
            </PropertyRow>
          </dl>
        </PropertyCard>
        <PropertyCard
          title={m.membersCardTitle}
          action={canManage ? <AddProjectMemberButton projectId={project.id} candidates={candidates} /> : undefined}
        >
          <ul className="flex flex-col gap-2 px-1 py-1">
            {members.map((member) => (
              <li key={member.userId} className="flex items-center justify-between gap-2">
                <Person person={member} />
                <span className="shrink-0 text-[0.8125rem] leading-5 text-muted-foreground">
                  {messages.roles[member.role]}
                </span>
              </li>
            ))}
          </ul>
        </PropertyCard>
      </aside>
    </div>
  );
}
