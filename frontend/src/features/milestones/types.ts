/** A stage of a project, with how far along its issues are (GET /api/v1/projects/{key}/milestones). */
export type Milestone = {
  id: string;
  name: string;
  /** YYYY-MM-DD */
  targetDate: string | null;
  /** Issues in the milestone, canceled ones not counted. */
  issueCount: number;
  doneCount: number;
  createdAt: string;
};

/** Share of the milestone's issues that are done, 0–100. An empty milestone is at 0. */
export function milestoneProgress(milestone: Pick<Milestone, "issueCount" | "doneCount">): number {
  return milestone.issueCount === 0 ? 0 : Math.round((milestone.doneCount / milestone.issueCount) * 100);
}
