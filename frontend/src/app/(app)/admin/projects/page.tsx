import type { Metadata } from "next";
import { notFound } from "next/navigation";
import { LayoutGridIcon } from "lucide-react";
import { ProjectIcon } from "@/components/project-icon";
import type { ProjectGitSettings } from "@/features/git/types";
import type { UserSummary } from "@/features/members/types";
import { AdminProjectList } from "@/features/projects/components/admin-project-list";
import { ProjectSettingsSections } from "@/features/projects/components/project-settings-sections";
import type { ProjectAccessRequest, ProjectMember, ProjectSummary } from "@/features/projects/types";
import { getProjects, requireUser, serverGet } from "@/lib/current-user";
import { messages } from "@/messages";

const m = messages.adminProjects;

export const metadata: Metadata = { title: m.title };

/**
 * The settings of any project, for the administrator (docs/features/admin-projects.md, D-236): the projects on the left,
 * the chosen one's settings on the right, the same sections as /project/[id]/settings. The administrator need not be a
 * member of the project: these pages reach it by the administrator's address (`/api/v1/admin/projects`).
 */
export default async function AdminProjectsPage({ searchParams }: PageProps<"/admin/projects">) {
  const requested = (await searchParams).project;
  // The same lists as everyone sees: the projects in use, and apart from them the ones that were put away (D-047).
  const [user, projects, archived] = await Promise.all([
    requireUser(),
    getProjects(),
    serverGet<ProjectSummary[]>("/api/v1/projects?archived=true").then((list) => list ?? []),
  ]);
  const selected = projects.find((project) => project.id === requested) ?? projects[0] ?? null;

  return (
    // data-wide: the administrator layout gives this page a wider column (admin/layout.tsx).
    <div data-wide className="flex flex-col gap-6">
      <div className="flex flex-col gap-1">
        <h2 className="text-[0.9375rem] leading-[1.375rem] font-semibold">{m.title}</h2>
        <p className="text-muted-foreground">{m.description}</p>
      </div>
      <div className="grid items-start gap-6 md:grid-cols-[17rem_minmax(0,1fr)]">
        <AdminProjectList projects={projects} archived={archived} selectedId={selected?.id ?? null} />
        {selected ? (
          <SelectedProjectSettings project={selected} currentUserId={user.id} />
        ) : (
          <p className="flex items-center gap-2 rounded-xl border px-5 py-6 text-[0.8125rem] leading-5 text-muted-foreground">
            <LayoutGridIcon className="size-4 shrink-0" aria-hidden="true" />
            {m.noSelection}
          </p>
        )}
      </div>
    </div>
  );
}

/** The chosen project's settings, read by the administrator's address; not found when the project is gone by now. */
async function SelectedProjectSettings({ project, currentUserId }: { project: ProjectSummary; currentUserId: string }) {
  const path = `/api/v1/admin/projects/${encodeURIComponent(project.id)}`;
  const [members, users, requests, git] = await Promise.all([
    serverGet<ProjectMember[]>(`${path}/members`),
    serverGet<UserSummary[]>("/api/v1/users").then((list) => list ?? []),
    serverGet<ProjectAccessRequest[]>(`${path}/access-requests`).then((list) => list ?? []),
    serverGet<ProjectGitSettings>(`${path}/git`),
  ]);
  if (!members) notFound();

  return (
    <article aria-label={project.name} className="flex min-w-0 flex-col gap-8 rounded-xl border bg-card px-5 py-6">
      <header className="flex items-center gap-3">
        <ProjectIcon iconUrl={project.iconUrl} size="lg" />
        <div className="flex min-w-0 flex-col">
          <h3 className="truncate text-[0.9375rem] leading-[1.375rem] font-semibold">{project.name}</h3>
          <span className="text-[0.8125rem] leading-5 text-muted-foreground">
            {project.member ? m.youAreMember : m.youAreNotMember}
          </span>
        </div>
      </header>
      <ProjectSettingsSections
        // A new project starts with its own forms and dialogs, not the last one's.
        key={project.id}
        scope="admin"
        project={project}
        members={members}
        users={users}
        requests={requests}
        git={git}
        currentUserId={currentUserId}
      />
    </article>
  );
}
