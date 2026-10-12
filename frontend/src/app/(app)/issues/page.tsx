import type { Metadata } from "next";
import { PageHeader } from "@/components/page-header";
import { IssuesView } from "@/features/issues/components/issues-view";
import type { Issue, Label } from "@/features/issues/types";
import type { UserSummary } from "@/features/members/types";
import type { ProjectMember } from "@/features/projects/types";
import { RealtimeScope } from "@/features/realtime/realtime-provider";
import { getProjects, serverGet } from "@/lib/current-user";
import { messages } from "@/messages";

export const metadata: Metadata = { title: messages.issues.all };

/** Every issue the signed-in person may see: the ones on their own and the ones in their projects. */
export default async function AllIssuesPage() {
  const [issues, users, labels, projects] = await Promise.all([
    serverGet<Issue[]>("/api/v1/issues"),
    serverGet<UserSummary[]>("/api/v1/users"),
    serverGet<Label[]>("/api/v1/labels"),
    getProjects(),
  ]);
  const people = (users ?? []).map((person): ProjectMember => ({ ...person, userId: person.id, role: "EDITOR" }));

  return (
    <>
      <RealtimeScope topics={["issues"]} />
      <PageHeader crumbs={[{ label: messages.issues.all }]} />
      <IssuesView
        projectKey={null}
        issues={issues ?? []}
        members={people}
        milestones={[]}
        labels={labels ?? []}
        projects={projects}
        canEdit
      />
    </>
  );
}
