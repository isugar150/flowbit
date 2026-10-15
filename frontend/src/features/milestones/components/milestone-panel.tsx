"use client";

import { createContext, useContext, useEffect, useRef } from "react";
import { useRouter } from "next/navigation";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Skeleton } from "@/components/ui/skeleton";
import { apiFetch } from "@/lib/api/client";
import { messages } from "@/messages";
import { updateMilestone } from "../api";
import type { Milestone } from "../types";
import { MilestoneDetailBody, useMilestoneNotes } from "./milestone-detail-dialog";

const m = messages.milestones;
/** Beside a ticket the milestone lists this many tickets; "목록에서 보기" has them all (D-143). */
const PANEL_ISSUE_LIMIT = 10;

export type MilestonePanelTarget = { projectId: string; milestoneId: string; canEdit: boolean };

const MilestonePanelContext = createContext<((target: MilestonePanelTarget | null) => void) | null>(null);

/** Given by the ticket dialog: which milestone its panel shows, or none (D-140). */
export const MilestonePanelProvider = MilestonePanelContext.Provider;

/** Sets the milestone beside the ticket; null outside a ticket dialog. */
export function useMilestonePanel() {
  return useContext(MilestonePanelContext);
}

/**
 * A milestone beside the ticket it was opened from (D-138, D-139): the whole document, which
 * people who may edit write in, and the milestone's tickets. On a wide screen (1280px and up) it
 * stands to the right of the ticket dialog; narrower, it comes below the ticket in the dialog's
 * scroll box. It lives inside the dialog's popup so the dialog's focus and
 * outside-press handling count it as part of it.
 */
export function MilestonePanel({ target }: { target: MilestonePanelTarget }) {
  const router = useRouter();
  const queryClient = useQueryClient();
  const key = ["milestones", target.projectId];
  const milestones = useQuery({
    queryKey: key,
    queryFn: () => apiFetch<Milestone[]>(`/api/v1/projects/${encodeURIComponent(target.projectId)}/milestones`),
    staleTime: 0,
  });
  const milestone = milestones.data?.find((item) => item.id === target.milestoneId);
  const notes = useMilestoneNotes(target.projectId, target.milestoneId);

  // A new name or date shows at once and is saved behind it.
  const change = (changes: { name?: string; targetDate?: string | null }) => {
    queryClient.setQueryData<Milestone[]>(key, (current) =>
      current?.map((item) => (item.id === target.milestoneId ? { ...item, ...changes } : item)),
    );
    void updateMilestone({ projectId: target.projectId, id: target.milestoneId, changes })
      .catch(() => undefined)
      .then(() => milestones.refetch());
  };

  // What was typed last is sent when the panel goes (the dialog closes, the milestone changes).
  const latest = useRef({ flush: notes.flush, dirty: notes.isDirty });
  useEffect(() => {
    latest.current = { flush: notes.flush, dirty: notes.isDirty };
  });
  useEffect(
    () => () => {
      if (latest.current.dirty()) void latest.current.flush().then(() => router.refresh());
    },
    [router],
  );

  return (
    <aside
      aria-label={milestone ? m.cardLabel(milestone.name) : m.stripLabel}
      className="mx-5 mt-6 mb-2 flex scroll-mt-2 flex-col overflow-hidden rounded-xl border bg-popover text-popover-foreground xl:absolute xl:top-0 xl:left-[calc(100%+0.75rem)] xl:m-0 xl:max-h-[88dvh] xl:min-h-full xl:w-[23rem] xl:border-0 xl:shadow-xl xl:ring-1 xl:ring-foreground/10"
    >
      <div className="flex h-11 shrink-0 items-center justify-between gap-2 border-b px-2">
        <span className="pl-2 text-xs font-medium text-muted-foreground">{m.panelTitle}</span>
      </div>
      <div className="xl:min-h-0 xl:flex-1 xl:overflow-y-auto xl:overscroll-contain">
        {milestone ? (
          <MilestoneDetailBody
            stacked
            issueLimit={PANEL_ISSUE_LIMIT}
            projectId={target.projectId}
            milestone={milestone}
            canEdit={target.canEdit}
            onChange={change}
            notes={notes}
          />
        ) : milestones.isError || (milestones.data && !milestone) ? (
          <p role="alert" className="p-5 text-[0.8125rem] leading-5 text-destructive">
            {m.panelFailed}
          </p>
        ) : (
          <div className="flex flex-col gap-3 p-5" aria-busy="true">
            <Skeleton className="h-6 w-2/3" />
            <Skeleton className="h-4 w-1/2" />
            <Skeleton className="h-32 w-full" />
          </div>
        )}
      </div>
    </aside>
  );
}
