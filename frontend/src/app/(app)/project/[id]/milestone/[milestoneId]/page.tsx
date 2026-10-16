import type { Metadata } from "next";
import { Suspense } from "react";
import { MilestoneRoute } from "@/features/milestones/components/milestone-route";
import type { Milestone } from "@/features/milestones/types";
import { ProjectOverview } from "@/features/projects/components/project-overview";
import { serverGet } from "@/lib/current-user";

type Params = PageProps<"/project/[id]/milestone/[milestoneId]">["params"];

/** The milestone's name in the browser tab when its address is opened directly. */
export async function generateMetadata({ params }: { params: Params }): Promise<Metadata> {
  const { id, milestoneId } = await params;
  const milestones = await serverGet<Milestone[]>(`/api/v1/projects/${encodeURIComponent(id)}/milestones`);
  const milestone = milestones?.find((item) => item.id === milestoneId);
  return milestone ? { title: milestone.name } : {};
}

/**
 * A milestone's address opened directly (a link, a bookmark, a reload) or from outside its
 * project (D-160): the project's overview with the milestone's dialog over it, the same as when it
 * is opened from the overview (`@milestone/(.)milestone/[milestoneId]`). Closing leaves the
 * overview.
 */
export default async function MilestonePage({ params }: { params: Params }) {
  const { id } = await params;
  return (
    <>
      <ProjectOverview id={id} />
      <Suspense fallback={null}>
        <DirectMilestone params={params} />
      </Suspense>
    </>
  );
}

async function DirectMilestone({ params }: { params: Params }) {
  const { id, milestoneId } = await params;
  return <MilestoneRoute projectId={id} milestoneId={milestoneId} closeTo={`/project/${encodeURIComponent(id)}`} />;
}
