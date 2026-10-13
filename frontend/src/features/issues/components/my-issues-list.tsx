import type { UserSummary } from "@/features/members/types";
import { usersAsMembers } from "@/features/projects/types";
import { getProjects, requireUser, serverGet } from "@/lib/current-user";
import { messages } from "@/messages";
import type { Issue, Label } from "../types";
import { IssuesView } from "./issues-view";

const m = messages.myIssues;

/**
 * "내 티켓": the signed-in person's issues from every project they can enter, in the same list as
 * everywhere else (search, filters, the three views). `scope` picks the ones assigned to them or
 * the ones they created; `deleted` shows the deleted ones instead.
 */
export async function MyIssuesList({ scope, deleted = false }: { scope: "assigned" | "created"; deleted?: boolean }) {
  const [user, issues, users, labels, projects] = await Promise.all([
    requireUser(),
    serverGet<Issue[]>(`/api/v1/me/issues?scope=${scope}${deleted ? "&deleted=true" : ""}`),
    serverGet<UserSummary[]>("/api/v1/users"),
    serverGet<Label[]>("/api/v1/labels"),
    getProjects(),
  ]);
  return (
    <IssuesView
      projectId={null}
      issues={issues ?? []}
      members={usersAsMembers(users)}
      milestones={[]}
      labels={labels ?? []}
      projects={projects}
      canEdit
      deleted={deleted}
      // An issue made under "담당" is one's own, so it appears in the list it was made from.
      defaultAssigneeId={scope === "assigned" ? user.id : null}
      empty={
        scope === "assigned"
          ? { title: m.emptyAssignedTitle, description: m.emptyAssigned }
          : { title: m.emptyCreatedTitle, description: m.emptyCreated }
      }
    />
  );
}
