import { notFound, redirect } from "next/navigation";
import { SettingsSection } from "@/components/settings-section";
import type { UserSummary } from "@/features/members/types";
import { ArchiveProjectSection } from "@/features/projects/components/archive-project-section";
import { ProjectAccessRequests } from "@/features/projects/components/project-access-requests";
import { ProjectDetailsForm } from "@/features/projects/components/project-details-form";
import { ProjectIconField } from "@/features/projects/components/project-icon-field";
import { ProjectMembersPanel } from "@/features/projects/components/project-members-panel";
import type { ProjectAccessRequest, ProjectMember } from "@/features/projects/types";
import { loadProject, requireUser, serverGet } from "@/lib/current-user";
import { messages } from "@/messages";

const m = messages.projects;

export default async function ProjectSettingsPage({ params }: PageProps<"/project/[id]/settings">) {
  const { id } = await params;
  const path = `/api/v1/projects/${encodeURIComponent(id)}`;
  const [user, project, members] = await Promise.all([
    requireUser(),
    loadProject(id),
    serverGet<ProjectMember[]>(`${path}/members`),
  ]);
  if (!members) notFound();
  // Only the project's managers have settings to look at (D-131); others go to the overview.
  if (project.myRole !== "MANAGER") redirect(`/project/${project.id}`);
  const canManage = true;
  // The list of people to add, and who is waiting to be let in, are only for those who can add.
  const [users, requests] = canManage
    ? await Promise.all([
        serverGet<UserSummary[]>("/api/v1/users").then((list) => list ?? []),
        serverGet<ProjectAccessRequest[]>(`${path}/access-requests`).then((list) => list ?? []),
      ])
    : [[], []];

  return (
    <div className="mx-auto flex w-full max-w-2xl flex-col gap-10 px-6 py-10">
      <SettingsSection title={m.settingsTitle} description={m.settingsDescription}>
        {canManage ? <ProjectIconField projectId={project.id} iconUrl={project.iconUrl} /> : null}
        <ProjectDetailsForm project={project} canManage={canManage} />
      </SettingsSection>
      <SettingsSection title={m.membersTitle} description={m.membersDescription}>
        <ProjectMembersPanel
          projectId={project.id}
          members={members}
          users={users}
          canManage={canManage}
          currentUserId={user.id}
        />
        {/* Below the members: people waiting to be let in (D-128). Notifications about a request open this. */}
        {canManage ? <ProjectAccessRequests projectId={project.id} requests={requests} /> : null}
      </SettingsSection>
      {canManage ? (
        <SettingsSection title={m.archiveTitle} description={m.archiveDescription}>
          <ArchiveProjectSection projectId={project.id} />
        </SettingsSection>
      ) : null}
    </div>
  );
}
