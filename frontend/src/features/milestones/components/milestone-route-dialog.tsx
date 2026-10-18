"use client";

import { usePathname, useRouter } from "next/navigation";
import { useOptimisticMutation } from "@/lib/use-refreshing-mutation";
import { updateMilestone } from "../api";
import type { Milestone } from "../types";
import { milestoneHref } from "./milestone-card";
import { MilestoneDetailDialog, type MilestoneChanges } from "./milestone-detail-dialog";

/**
 * The milestone dialog that belongs to the milestone's address (D-160). Opened from inside the
 * project, closing is going back; opened by its address, `closeTo` says where closing leads.
 */
export function MilestoneRouteDialog({
  projectId,
  milestone: saved,
  canEdit,
  closeTo,
}: {
  projectId: string;
  milestone: Milestone;
  canEdit: boolean;
  closeTo?: string;
}) {
  const router = useRouter();
  const pathname = usePathname();
  // A new name, status or date shows at once and is saved behind it; the overview behind follows the refresh.
  const update = useOptimisticMutation(
    saved,
    (milestone, changes: MilestoneChanges) => ({ ...milestone, ...changes }),
    (changes: MilestoneChanges) => updateMilestone({ projectId, id: saved.id, changes }),
  );
  // Moving on by a link inside the dialog leaves its slot showing what it last showed, so the
  // address says whether it still belongs on screen. An issue opened from its list comes over it.
  const own = decodeURIComponent(milestoneHref(projectId, saved.id));
  if (decodeURIComponent(pathname) !== own && !pathname.startsWith("/issue/")) return null;

  return (
    <MilestoneDetailDialog
      projectId={projectId}
      milestone={update.value}
      canEdit={canEdit}
      onChange={update.mutate}
      onClose={() => {
        if (closeTo) router.push(closeTo);
        else router.back();
      }}
    />
  );
}
