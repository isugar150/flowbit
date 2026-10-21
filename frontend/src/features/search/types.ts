import type { IssuePriority, IssueStatus } from "@/features/issues/types";

export type IssueMatch = "KEY" | "TITLE" | "DESCRIPTION" | "COMMENT";

export interface SearchProject {
  id: string;
  name: string;
}

export interface IssueHit {
  id: string;
  key: string;
  number: number;
  title: string;
  status: IssueStatus;
  priority: IssuePriority;
  project: SearchProject | null;
  match: IssueMatch;
  /** A piece of the body or the comment the words were found in. */
  snippet: string | null;
  updatedAt: string;
}

export interface DocumentHit {
  id: string;
  title: string;
  updatedAt: string;
  /** Exactly one of the two is set: where the document belongs. */
  issue: { key: string; title: string } | null;
  project: SearchProject | null;
  snippet: string | null;
}

/** A project found by its name or by what is written in its overview (D-166). */
export interface ProjectHit {
  id: string;
  name: string;
  updatedAt: string;
  snippet: string | null;
}

/** A milestone found by its name or by what is written in it (D-166). */
export interface MilestoneHit {
  id: string;
  name: string;
  project: SearchProject;
  updatedAt: string;
  snippet: string | null;
}

/** GET /api/v1/search (docs/features/search.md). */
export interface SearchResult {
  issues: IssueHit[];
  documents: DocumentHit[];
  projects: ProjectHit[];
  milestones: MilestoneHit[];
  issuesMore: boolean;
  documentsMore: boolean;
  projectsMore: boolean;
  milestonesMore: boolean;
}
