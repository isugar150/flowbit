import { notFound } from "next/navigation";
import { ProjectIcon } from "@/components/project-icon";
import { PropertyCard, PropertyRow } from "@/components/property-card";
import { ProjectIssueSummary } from "@/features/issues/components/project-issue-summary";
import type { Issue, IssueDocumentSummary } from "@/features/issues/types";
import { MilestonesSection } from "@/features/milestones/components/milestones-section";
import type { Milestone } from "@/features/milestones/types";
import { ProjectMemberList } from "@/features/projects/components/project-member-list";
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
  const [user, project, members, projects, milestones, updates, documents, issues] = await Promise.all([
    requireUser(),
    loadProject(id),
    serverGet<ProjectMember[]>(`${path}/members`),
    getProjects(),
    serverGet<Milestone[]>(`${path}/milestones`),
    serverGet<ProjectUpdate[]>(`${path}/updates`),
    serverGet<IssueDocumentSummary[]>(`${path}/documents`),
    serverGet<Issue[]>(`${path}/issues`),
  ]);
  if (!members || !milestones || !updates) notFound();
  const canManage = project.myRole === "MANAGER";
  const labelSuggestions = [...new Set(projects.flatMap((other) => other.labels))].sort((a, b) => a.localeCompare(b, "ko"));
  const created = formatDate(project.createdAt, user.timezone);

  return (
    <div className="flex flex-1 flex-col gap-8 px-4 pt-2 pb-10 lg:flex-row lg:items-start lg:gap-6">
      <div className="mx-auto flex w-full max-w-3xl min-w-0 flex-1 flex-col gap-6 px-2 pt-8 sm:px-6 lg:pt-14">
        <div className="flex flex-col gap-3">
          <ProjectIcon iconUrl={project.iconUrl} size="lg" />
          <h2 className="text-2xl leading-8 font-semibold">{project.name}</h2>
        </div>
        <ProjectOverviewEditor project={project} documents={documents ?? []} canEdit={project.myRole !== "VIEWER"} />
        <ProjectIssueSummary projectId={project.id} issues={issues ?? []} canEdit={project.myRole !== "VIEWER"} />
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
            <PropertyRow label={m.propMembers}>{m.memberCount(project.memberCount)}</PropertyRow>
            <PropertyRow label={m.propCreated}>
              <span className="tabular-nums">{created}</span>
            </PropertyRow>
          </dl>
        </PropertyCard>
        {/* Who is in the project, nothing more: people are added, removed and given roles in the settings. */}
        <PropertyCard title={m.membersCardTitle}>
          <ProjectMemberList members={members} currentUserId={user.id} />
        </PropertyCard>
      </aside>
    </div>
  );
}
