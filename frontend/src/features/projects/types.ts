import type { RichDoc } from "@/components/editor/rich-text-editor";
import type { IssuePerson, IssuePriority } from "@/features/issues/types";

export type ProjectRole = "MANAGER" | "EDITOR" | "VIEWER";
export type ProjectStatus = "BACKLOG" | "PLANNED" | "IN_PROGRESS" | "COMPLETED" | "CANCELED";

/** A project as the signed-in user sees it (GET /api/v1/projects, /api/v1/projects/{key}). */
export type Project = {
  id: string;
  key: string;
  name: string;
  /** One-line summary. */
  description: string;
  /** What the signed-in user may do in this project. */
  myRole: ProjectRole;
  memberCount: number;
  createdAt: string;
  status: ProjectStatus;
  priority: IssuePriority;
  lead: IssuePerson | null;
  /** YYYY-MM-DD */
  startDate: string | null;
  /** YYYY-MM-DD, never before startDate */
  targetDate: string | null;
  labels: string[];
  /** Issues of the project, canceled ones not counted. */
  issueCount: number;
  doneCount: number;
  /** Name of the first milestone that is not finished yet. */
  currentMilestone: string | null;
  /** The overview document. Only sent when a single project is requested; null in lists. */
  content: RichDoc | null;
};

export type ProjectMember = {
  userId: string;
  loginId: string;
  displayName: string | null;
  avatarUrl: string | null;
  role: ProjectRole;
};

/** Only the fields present are changed; null clears the lead or a date. */
export type ProjectChanges = Partial<{
  name: string;
  description: string;
  content: RichDoc | null;
  status: ProjectStatus;
  priority: IssuePriority;
  leadId: string | null;
  startDate: string | null;
  targetDate: string | null;
  labels: string[];
}>;
