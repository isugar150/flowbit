import { notFound } from "next/navigation";
import { IssuesView } from "@/features/issues/components/issues-view";
import type { Issue, Label } from "@/features/issues/types";
import type { Milestone } from "@/features/milestones/types";
import type { ProjectMember } from "@/features/projects/types";
import { getProjects, loadProject, serverGet } from "@/lib/current-user";

export default async function ProjectIssuesPage({ params }: PageProps<"/project/[key]/issues">) {
  const { key } = await params;
  const path = `/api/v1/projects/${encodeURIComponent(key)}`;
  const [project, issues, members, milestones, labels, projects] = await Promise.all([
    loadProject(key),
    serverGet<Issue[]>(`${path}/issues`),
    serverGet<ProjectMember[]>(`${path}/members`),
    serverGet<Milestone[]>(`${path}/milestones`),
    serverGet<Label[]>("/api/v1/labels"),
    getProjects(),
  ]);
  if (!issues || !members || !milestones || !labels) notFound();

  return (
    <IssuesView
      projectKey={project.key}
      issues={issues}
      members={members}
      milestones={milestones}
      labels={labels}
      projects={projects}
      canEdit={project.myRole !== "VIEWER"}
    />
  );
}
