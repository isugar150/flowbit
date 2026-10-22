import type { UserSummary } from "@/features/members/types";
import { usersAsMembers } from "@/features/projects/types";
import { getProjects, requireUser, serverGet } from "@/lib/current-user";
import { messages } from "@/messages";
import type { Issue, Label } from "../types";
import { IssuesView } from "./issues-view";

const m = messages.myIssues;

/**
 * "내 티켓": the signed-in person's issues from every project they can enter, in the same list as
 * everywhere else (search, filters, the three views). `scope` picks the ones they asked for (created) or
 * the ones assigned to them. A new issue starts with nobody assigned on either tab; `deleted` shows the deleted ones instead.
 */
export async function MyIssuesList({ scope, deleted = false }: { scope: "assigned" | "created"; deleted?: boolean }) {
  const [, issues, users, labels, projects] = await Promise.all([
    requireUser(),
    serverGet<Issue[]>(`/api/v1/me/issues?scope=${scope}${deleted ? "&deleted=true" : ""}`),
    serverGet<UserSummary[]>("/api/v1/users"),
    serverGet<Label[]>("/api/v1/labels"),
    getProjects(),
  ]);
  return (
    <IssuesView
      projectId={null}
      underTabs
      issues={issues ?? []}
      members={usersAsMembers(users)}
      labels={labels ?? []}
      projects={projects}
      canEdit
      deleted={deleted}
      // Every issue on this tab is assigned to the person; picking an assignee would only hide them.
      assigneeFilter={scope !== "assigned"}
      empty={
        scope === "assigned"
          ? { title: m.emptyAssignedTitle, description: m.emptyAssigned }
          : { title: m.emptyCreatedTitle, description: m.emptyCreated }
      }
    />
  );
}
