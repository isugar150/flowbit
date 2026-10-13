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

/** Share of the milestone's issues that are done, 0–100. An empty milestone is at 0. */
export function milestoneProgress(milestone: Pick<Milestone, "issueCount" | "doneCount">): number {
  return milestone.issueCount === 0 ? 0 : Math.round((milestone.doneCount / milestone.issueCount) * 100);
}
