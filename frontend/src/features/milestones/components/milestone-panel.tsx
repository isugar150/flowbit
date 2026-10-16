"use client";

import { createContext, useContext } from "react";
import { useQuery } from "@tanstack/react-query";
import { Maximize2Icon, Minimize2Icon } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { apiFetch } from "@/lib/api/client";
import { messages } from "@/messages";
import type { Milestone } from "../types";
import { milestoneHref } from "./milestone-card";
import { MilestoneDetailBody } from "./milestone-detail-dialog";

const m = messages.milestones;
/** Beside a ticket the milestone lists this many tickets; "목록에서 보기" has them all (D-143). */
const PANEL_ISSUE_LIMIT = 10;

export type MilestonePanelTarget = { projectId: string; milestoneId: string };

const MilestonePanelContext = createContext<((target: MilestonePanelTarget | null) => void) | null>(null);

/** Given by the ticket dialog: which milestone its panel shows, or none (D-140). */
export const MilestonePanelProvider = MilestonePanelContext.Provider;

/** Sets the milestone beside the ticket; null outside a ticket dialog. */
export function useMilestonePanel() {
  return useContext(MilestonePanelContext);
}

/**
 * A milestone beside the ticket it was opened from (D-138, D-139): the whole document and the
 * milestone's tickets, to read. Nothing is changed here; the link by the name leads to the
 * milestone in its project, where it is written (D-157). On a wide screen (1280px and up) it
 * stands to the right of the ticket dialog and can be made wide, the ticket then narrow; narrower,
 * it comes below the ticket in the dialog's scroll box. It lives inside the dialog's popup so the
 * dialog's focus and outside-press handling count it as part of it.
 */
export function MilestonePanel({
  target,
  wide,
  onWideChange,
}: {
  target: MilestonePanelTarget;
  wide: boolean;
  onWideChange: (wide: boolean) => void;
}) {
  const milestones = useQuery({
    queryKey: ["milestones", target.projectId],
    queryFn: () => apiFetch<Milestone[]>(`/api/v1/projects/${encodeURIComponent(target.projectId)}/milestones`),
    staleTime: 0,
  });
  const milestone = milestones.data?.find((item) => item.id === target.milestoneId);
  const WideIcon = wide ? Minimize2Icon : Maximize2Icon;

  return (
    <aside
      aria-label={milestone ? m.cardLabel(milestone.name) : m.stripLabel}
      className="mx-5 mt-6 mb-2 flex scroll-mt-2 flex-col overflow-hidden rounded-xl border bg-popover text-popover-foreground xl:absolute xl:top-0 xl:left-[calc(100%+0.75rem)] xl:m-0 xl:max-h-[88dvh] xl:min-h-full xl:w-(--milestone-panel-width) xl:border-0 xl:shadow-xl xl:ring-1 xl:ring-foreground/10"
    >
      <div className="flex h-11 shrink-0 items-center justify-between gap-2 border-b px-2">
        <span className="pl-2 text-xs font-medium text-muted-foreground">{m.panelTitle}</span>
        {/* Only where the panel stands beside the ticket; below it, it is already as wide as the ticket. */}
        <Button
          variant="ghost"
          size="icon-sm"
          className="hidden text-muted-foreground xl:inline-flex"
          aria-pressed={wide}
          aria-label={wide ? m.panelNarrow : m.panelWiden}
          title={wide ? m.panelNarrow : m.panelWiden}
          onClick={() => onWideChange(!wide)}
        >
          <WideIcon aria-hidden="true" />
        </Button>
      </div>
      <div className="xl:min-h-0 xl:flex-1 xl:overflow-y-auto xl:overscroll-contain">
        {milestone ? (
          <MilestoneDetailBody
            stacked
            issueLimit={PANEL_ISSUE_LIMIT}
            projectId={target.projectId}
            milestone={milestone}
            canEdit={false}
            openHref={milestoneHref(target.projectId, target.milestoneId)}
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
