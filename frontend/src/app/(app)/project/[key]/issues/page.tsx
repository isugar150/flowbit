import { notFound } from "next/navigation";
import { IssuesView } from "@/features/issues/components/issues-view";
import type { Issue } from "@/features/issues/types";
import type { Project, ProjectMember } from "@/features/projects/types";
import { serverGet } from "@/lib/current-user";

export default async function ProjectIssuesPage({ params }: PageProps<"/project/[key]/issues">) {
  const { key } = await params;
  const path = `/api/v1/projects/${encodeURIComponent(key)}`;
  const [project, issues, members] = await Promise.all([
    serverGet<Project>(path),
    serverGet<Issue[]>(`${path}/issues`),
    serverGet<ProjectMember[]>(`${path}/members`),
  ]);
  if (!project || !issues || !members) notFound();

  return (
    <IssuesView projectKey={project.key} issues={issues} members={members} canEdit={project.myRole !== "VIEWER"} />
  );
}
