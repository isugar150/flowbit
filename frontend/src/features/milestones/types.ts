import type { RichDoc } from "@/components/editor/rich-text-editor";

/** A stage of a project, with how far along its issues are (GET /api/v1/projects/{id}/milestones). */
export type Milestone = {
  id: string;
  name: string;
  /** YYYY-MM-DD */
  targetDate: string | null;
  /** Issues in the milestone, canceled ones not counted. */
  issueCount: number;
  doneCount: number;
  createdAt: string;
  /** The milestone's own document (where things stand), or null when nothing was written. */
  content: RichDoc | null;
};

/**
 * A milestone is done when it has tickets and every one of them is done, canceled ones not counted
 * (D-136). Nothing is stored: putting a new ticket in it opens it again.
 */
export function isMilestoneDone(milestone: Partial<Pick<Milestone, "issueCount" | "doneCount">>): boolean {
  const count = milestone.issueCount ?? 0;
  return count > 0 && milestone.doneCount === count;
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
