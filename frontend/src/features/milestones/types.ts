import type { RichDoc } from "@/components/editor/rich-text-editor";
import type { ProjectStatus } from "@/features/projects/types";

/** Where a milestone stands, set by hand (D-161): the same values, names and marks as a project's status. */
export type MilestoneStatus = ProjectStatus;

/** A stage of a project, with how far along its issues are (GET /api/v1/projects/{id}/milestones). */
export type Milestone = {
  id: string;
  name: string;
  /** YYYY-MM-DD */
  targetDate: string | null;
  status: MilestoneStatus;
  /** Issues in the milestone, canceled ones not counted. */
  issueCount: number;
  doneCount: number;
  createdAt: string;
  /** The milestone's own document (where things stand), or null when nothing was written. */
  content: RichDoc | null;
};

/**
 * A completed or canceled milestone is closed (D-161): it is folded away in the lists and left out
 * of the choices. Its tickets do not decide it; the status is set by hand.
 */
export function isMilestoneClosed(milestone: Partial<Pick<Milestone, "status">>): boolean {
  return milestone.status === "COMPLETED" || milestone.status === "CANCELED";
}

/** "6/10 · 60% · 10-31": how far a milestone is, for a group heading or a chip's title. */
export function milestoneSummary(milestone: Milestone, thisYear: number | null): string {
  const parts = [`${milestone.doneCount}/${milestone.issueCount}`, `${milestoneProgress(milestone)}%`];
  if (milestone.targetDate) parts.push(shortDate(milestone.targetDate, thisYear));
  return parts.join(" · ");
}

/**
 * YYYY-MM-DD as MM-DD this year, the whole date otherwise (docs/01-product.md section 7).
 * `thisYear` comes from `useThisYear()`: null on the server, which may not read the clock, and then
 * the whole date is shown.
 */
export function shortDate(date: string, thisYear: number | null): string {
  return thisYear !== null && date.startsWith(`${thisYear}-`) ? date.slice(5) : date;
}

/** Share of the milestone's issues that are done, 0–100. An empty milestone is at 0. */
export function milestoneProgress(milestone: Pick<Milestone, "issueCount" | "doneCount">): number {
  return milestone.issueCount === 0 ? 0 : Math.round((milestone.doneCount / milestone.issueCount) * 100);
}
