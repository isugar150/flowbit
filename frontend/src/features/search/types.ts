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

/** GET /api/v1/search (docs/features/search.md). */
export interface SearchResult {
  issues: IssueHit[];
  documents: DocumentHit[];
  issuesMore: boolean;
  documentsMore: boolean;
}
