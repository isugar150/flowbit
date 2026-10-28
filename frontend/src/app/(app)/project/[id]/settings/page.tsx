import { notFound, redirect } from "next/navigation";
import type { ProjectGitSettings } from "@/features/git/types";
import type { UserSummary } from "@/features/members/types";
import { ProjectSettingsSections } from "@/features/projects/components/project-settings-sections";
import type { ProjectAccessRequest, ProjectMember } from "@/features/projects/types";
import { loadProject, requireUser, serverGet } from "@/lib/current-user";

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
  // The list of people to add, and who is waiting to be let in, are only for those who can add.
  const [users, requests, git] = await Promise.all([
    serverGet<UserSummary[]>("/api/v1/users").then((list) => list ?? []),
    serverGet<ProjectAccessRequest[]>(`${path}/access-requests`).then((list) => list ?? []),
    serverGet<ProjectGitSettings>(`${path}/git`),
  ]);

  return (
    <div className="mx-auto flex w-full max-w-5xl flex-col gap-10 px-6 py-10">
      <ProjectSettingsSections
        project={project}
        members={members}
        users={users}
        requests={requests}
        git={git}
        currentUserId={user.id}
      />
    </div>
  );
}
