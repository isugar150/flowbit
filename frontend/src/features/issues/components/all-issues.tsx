import { PageHeader } from "@/components/page-header";
import type { UserSummary } from "@/features/members/types";
import { usersAsMembers } from "@/features/projects/types";
import { RealtimeScope } from "@/features/realtime/realtime-provider";
import { getProjects, serverGet } from "@/lib/current-user";
import { messages } from "@/messages";
import { pageLimit, splitPage } from "../paging";
import type { Issue, Label } from "../types";
import { IssuesView } from "./issues-view";

/**
 * Every issue the signed-in person may see: the ones on their own and the ones in their projects.
 * The page at /issues, and what stands behind an issue's dialog when its address is opened directly.
 * `?project=` (from a project's overview) narrows it to that project's issues.
 */
export async function AllIssues({ deleted = false, pages = 1 }: { deleted?: boolean; pages?: number }) {
  const [list, users, labels, projects] = await Promise.all([
    serverGet<Issue[]>(`/api/v1/issues?limit=${pageLimit(pages)}${deleted ? "&deleted=true" : ""}`),
    serverGet<UserSummary[]>("/api/v1/users"),
    serverGet<Label[]>("/api/v1/labels"),
    getProjects(),
  ]);
  const { items: issues, hasMore } = splitPage(list, pages);
  return (
    <>
      <RealtimeScope topics={["issues"]} />
      <PageHeader crumbs={[{ label: messages.issues.all }]} />
      <IssuesView
        projectId={null}
        issues={issues}
        hasMore={hasMore}
        pages={pages}
        members={usersAsMembers(users)}
        labels={labels ?? []}
        projects={projects}
        canEdit
        deleted={deleted}
      />
    </>
  );
}
