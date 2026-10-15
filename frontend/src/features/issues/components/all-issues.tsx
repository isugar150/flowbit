import { PageHeader } from "@/components/page-header";
import type { UserSummary } from "@/features/members/types";
import { usersAsMembers } from "@/features/projects/types";
import { RealtimeScope } from "@/features/realtime/realtime-provider";
import { getProjects, serverGet } from "@/lib/current-user";
import { messages } from "@/messages";
import type { Milestone } from "@/features/milestones/types";
import type { Issue, Label } from "../types";
import { IssuesView } from "./issues-view";

/**
 * Every issue the signed-in person may see: the ones on their own and the ones in their projects.
 * The page at /issues, and what stands behind an issue's dialog when its address is opened directly.
 */
export async function AllIssues({ deleted = false, projectId = null }: { deleted?: boolean; projectId?: string | null }) {
  const [issues, users, labels, projects, milestones] = await Promise.all([
    serverGet<Issue[]>(deleted ? "/api/v1/issues?deleted=true" : "/api/v1/issues"),
    serverGet<UserSummary[]>("/api/v1/users"),
    serverGet<Label[]>("/api/v1/labels"),
    getProjects(),
    // The milestones of the project the list is filtered to, so they can be filtered by too.
    projectId ? serverGet<Milestone[]>(`/api/v1/projects/${encodeURIComponent(projectId)}/milestones`) : null,
  ]);
  return (
    <>
      <RealtimeScope topics={["issues"]} />
      <PageHeader crumbs={[{ label: messages.issues.all }]} />
      <IssuesView
        projectId={null}
        issues={issues ?? []}
        members={usersAsMembers(users)}
        milestones={milestones ?? []}
        milestoneProjectId={milestones ? projectId : null}
        labels={labels ?? []}
        projects={projects}
        canEdit
        deleted={deleted}
      />
    </>
  );
}
