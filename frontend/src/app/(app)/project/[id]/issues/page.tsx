import { notFound } from "next/navigation";
import { IssuesView } from "@/features/issues/components/issues-view";
import type { Issue, Label } from "@/features/issues/types";
import type { Milestone } from "@/features/milestones/types";
import type { ProjectMember } from "@/features/projects/types";
import { getProjects, loadProject, serverGet } from "@/lib/current-user";

/** `?deleted=1` shows the deleted issues instead of the ones in use. */
export default async function ProjectIssuesPage({ params, searchParams }: PageProps<"/project/[id]/issues">) {
  const [{ id }, { deleted }] = await Promise.all([params, searchParams]);
  const path = `/api/v1/projects/${encodeURIComponent(id)}`;
  const [project, issues, members, milestones, labels, projects] = await Promise.all([
    loadProject(id),
    serverGet<Issue[]>(`${path}/issues${deleted ? "?deleted=true" : ""}`),
    serverGet<ProjectMember[]>(`${path}/members`),
    serverGet<Milestone[]>(`${path}/milestones`),
    serverGet<Label[]>("/api/v1/labels"),
    getProjects(),
  ]);
  if (!issues || !members || !milestones || !labels) notFound();

  return (
    <IssuesView
      projectId={project.id}
      underTabs
      issues={issues}
      members={members}
      milestones={milestones}
      labels={labels}
      projects={projects}
      deleted={Boolean(deleted)}
      canEdit={project.myRole !== "VIEWER"}
    />
  );
}
