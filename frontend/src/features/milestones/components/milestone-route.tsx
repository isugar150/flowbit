import { notFound } from "next/navigation";
import { loadProject, serverGet } from "@/lib/current-user";
import type { Milestone } from "../types";
import { MilestoneRouteDialog } from "./milestone-route-dialog";

/**
 * The milestone a `/project/{id}/milestone/{milestoneId}` address names, in its dialog (D-160).
 * A project the person may not enter leads to asking for access, and a milestone that is not
 * there is not found, as with an issue's address.
 */
export async function MilestoneRoute({ projectId, milestoneId, closeTo }: { projectId: string; milestoneId: string; closeTo?: string }) {
  const project = await loadProject(projectId);
  const milestones = await serverGet<Milestone[]>(`/api/v1/projects/${encodeURIComponent(project.id)}/milestones`);
  const milestone = milestones?.find((item) => item.id === milestoneId);
  if (!milestone) notFound();
  return <MilestoneRouteDialog projectId={project.id} milestone={milestone} canEdit={project.myRole !== "VIEWER"} closeTo={closeTo} />;
}
