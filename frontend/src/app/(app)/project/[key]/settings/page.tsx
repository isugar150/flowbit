import { notFound } from "next/navigation";
import { SettingsSection } from "@/components/settings-section";
import type { UserSummary } from "@/features/members/types";
import { ProjectDetailsForm } from "@/features/projects/components/project-details-form";
import { ProjectMembersPanel } from "@/features/projects/components/project-members-panel";
import type { ProjectMember } from "@/features/projects/types";
import { loadProject, requireUser, serverGet } from "@/lib/current-user";
import { messages } from "@/messages";

const m = messages.projects;

export default async function ProjectSettingsPage({ params }: PageProps<"/project/[key]/settings">) {
  const { key } = await params;
  const path = `/api/v1/projects/${encodeURIComponent(key)}`;
  const [user, project, members] = await Promise.all([
    requireUser(),
    loadProject(key),
    serverGet<ProjectMember[]>(`${path}/members`),
  ]);
  if (!members) notFound();
  const canManage = project.myRole === "MANAGER";
  // The list of people to add is only needed by those who can add.
  const users = canManage ? ((await serverGet<UserSummary[]>("/api/v1/users")) ?? []) : [];

  return (
    <div className="mx-auto flex w-full max-w-2xl flex-col gap-10 px-6 py-10">
      <SettingsSection title={m.settingsTitle} description={m.settingsDescription}>
        <ProjectDetailsForm project={project} canManage={canManage} />
      </SettingsSection>
      <SettingsSection title={m.membersTitle} description={m.membersDescription}>
        <ProjectMembersPanel
          projectKey={project.key}
          members={members}
          users={users}
          canManage={canManage}
          currentUserId={user.id}
        />
      </SettingsSection>
    </div>
  );
}
