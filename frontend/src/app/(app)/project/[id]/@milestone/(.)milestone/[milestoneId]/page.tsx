import { Suspense } from "react";
import { MilestoneRoute } from "@/features/milestones/components/milestone-route";

type Params = PageProps<"/project/[id]/milestone/[milestoneId]">["params"];

/**
 * Intercepts a move from inside the project to `/project/{id}/milestone/{milestoneId}` (D-160) and
 * shows the milestone in a dialog over the overview, which stays as it was. Loading the address
 * directly, or coming to it from outside the project, renders `milestone/[milestoneId]/page.tsx`.
 */
export default function MilestoneModalPage({ params }: { params: Params }) {
  return (
    <Suspense fallback={null}>
      <InterceptedMilestone params={params} />
    </Suspense>
  );
}

async function InterceptedMilestone({ params }: { params: Params }) {
  const { id, milestoneId } = await params;
  return <MilestoneRoute projectId={id} milestoneId={milestoneId} />;
}
