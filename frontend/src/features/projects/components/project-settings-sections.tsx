import { ProjectAutomations } from "@/features/automations/project-automations";
import { SettingsSection } from "@/components/settings-section";
import { CycleSettingsField } from "@/features/cycles/components/cycle-settings-field";
import { ProjectGitRepositories } from "@/features/git/components/project-git-repositories";
import type { ProjectGitSettings } from "@/features/git/types";
import type { UserSummary } from "@/features/members/types";
import { messages } from "@/messages";
import { ProjectApiScopeProvider } from "../project-api-scope";
import type { ProjectApiScope } from "../scope";
import type { Project, ProjectAccessRequest, ProjectMember } from "../types";
import { ArchiveProjectSection } from "./archive-project-section";
import { ProjectAccessField } from "./project-access-field";
import { ProjectAccessRequests } from "./project-access-requests";
import { ProjectDetailsForm } from "./project-details-form";
import { ProjectEstimatesField } from "./project-estimates-field";
import { ProjectIconField } from "./project-icon-field";
import { ProjectMembersPanel } from "./project-members-panel";
import { ProjectTriageField } from "./project-triage-field";

const m = messages.projects;

/**
 * The sections of a project's settings, for someone who manages it. Both /project/[id]/settings and 관리자 설정 >
 * 프로젝트 (D-236) show these; `scope` "admin" makes every section reach the project by the administrator's address, which
 * works for a project the signed-in administrator was not added to. Put them in a column: they are sections of a page.
 */
export function ProjectSettingsSections({
  project,
  members,
  users,
  requests,
  git,
  currentUserId,
  scope = "project",
}: {
  project: Pick<Project, "id" | "name" | "iconUrl" | "openToAll" | "triageEnabled" | "estimatesEnabled">;
  /** Everyone who is in the project, added or not (the ones an open project lets in); only the added ones are listed. */
  members: ProjectMember[];
  /** Everyone who could be added. */
  users: UserSummary[];
  requests: ProjectAccessRequest[];
  git: ProjectGitSettings | null;
  currentUserId: string;
  scope?: ProjectApiScope;
}) {
  return (
    <ProjectApiScopeProvider scope={scope}>
      <SettingsSection title={m.settingsTitle} description={m.settingsDescription}>
        <ProjectIconField projectId={project.id} iconUrl={project.iconUrl} />
        <ProjectDetailsForm project={project} canManage />
      </SettingsSection>
      <SettingsSection title={m.accessTitle} description={m.accessDescription}>
        <ProjectAccessField projectId={project.id} openToAll={project.openToAll} />
      </SettingsSection>
      <SettingsSection title={messages.cycles.settingsTitle} description={messages.cycles.settingsDescription}>
        <CycleSettingsField projectId={project.id} />
      </SettingsSection>
      <SettingsSection title={m.triageTitle} description={m.triageDescription}>
        <ProjectTriageField projectId={project.id} enabled={project.triageEnabled} />
      </SettingsSection>
      <SettingsSection title={messages.automations.title} description={messages.automations.description}>
        <ProjectAutomations projectId={project.id} members={members} />
      </SettingsSection>
      <SettingsSection title={m.estimatesTitle} description={m.estimatesDescription}>
        <ProjectEstimatesField projectId={project.id} enabled={project.estimatesEnabled} />
      </SettingsSection>
      <SettingsSection title={m.membersTitle} description={m.membersDescription}>
        <ProjectMembersPanel
          projectId={project.id}
          // Only the people who were added: a project open to everyone lists every member of the workspace (D-192).
          members={members.filter((member) => member.direct)}
          openToAll={project.openToAll}
          users={users}
          canManage
          currentUserId={currentUserId}
        />
        {/* Below the members: people waiting to be let in (D-128). Notifications about a request open this. */}
        <ProjectAccessRequests projectId={project.id} requests={requests} />
      </SettingsSection>
      {/* Only while Git is in use (D-145); a project that already chose repositories keeps seeing them. */}
      {git && (git.enabled || git.repositories.length > 0) ? (
        <SettingsSection title={messages.git.repositories.title} description={messages.git.repositories.description}>
          <ProjectGitRepositories projectId={project.id} settings={git} />
        </SettingsSection>
      ) : null}
      <SettingsSection title={m.archiveTitle} description={m.archiveDescription}>
        <ArchiveProjectSection projectId={project.id} />
      </SettingsSection>
    </ProjectApiScopeProvider>
  );
}
