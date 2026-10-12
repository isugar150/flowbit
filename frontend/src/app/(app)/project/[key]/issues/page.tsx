import { notFound } from "next/navigation";
import { IssuesView } from "@/features/issues/components/issues-view";
import type { Issue } from "@/features/issues/types";
import type { Milestone } from "@/features/milestones/types";
import type { Project, ProjectMember } from "@/features/projects/types";
import { serverGet } from "@/lib/current-user";

export default async function ProjectIssuesPage({ params, searchParams }: PageProps<"/project/[key]/issues">) {
  const [{ key }, { milestone }] = await Promise.all([params, searchParams]);
  const path = `/api/v1/projects/${encodeURIComponent(key)}`;
  const [project, issues, members, milestones] = await Promise.all([
    serverGet<Project>(path),
    serverGet<Issue[]>(`${path}/issues`),
    serverGet<ProjectMember[]>(`${path}/members`),
    serverGet<Milestone[]>(`${path}/milestones`),
  ]);
  if (!project || !issues || !members || !milestones) notFound();

  return (
    <IssuesView
      projectKey={project.key}
      issues={issues}
      members={members}
      milestones={milestones}
      initialMilestone={typeof milestone === "string" ? milestone : undefined}
      canEdit={project.myRole !== "VIEWER"}
    />
  );
}
