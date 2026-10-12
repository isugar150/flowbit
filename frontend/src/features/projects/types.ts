import type { RichDoc } from "@/components/editor/rich-text-editor";
import type { IssuePerson, IssuePriority } from "@/features/issues/types";

export type ProjectRole = "MANAGER" | "EDITOR" | "VIEWER";
export type ProjectStatus = "BACKLOG" | "PLANNED" | "IN_PROGRESS" | "COMPLETED" | "CANCELED";
export type ProjectHealth = "ON_TRACK" | "AT_RISK" | "OFF_TRACK";

/** A project as the signed-in user sees it (GET /api/v1/projects, /api/v1/projects/{key}). */
export type Project = {
  id: string;
  key: string;
  name: string;
  /** One-line summary. */
  description: string;
  /** The uploaded icon; null shows the default box. */
  iconUrl: string | null;
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
  /** Health of the latest project update and when it was posted; null before the first update. */
  health: ProjectHealth | null;
  healthUpdatedAt: string | null;
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

/**
 * A project in the list (GET /api/v1/projects). Everyone sees every project; `myRole` is null
 * for one the signed-in user has not been added to, which they cannot enter.
 */
export type ProjectSummary = Omit<Project, "myRole"> & { myRole: ProjectRole | null };

/** Whether the signed-in user may enter a project, and whom to ask (GET /api/v1/projects/{key}/access). */
export type ProjectEntry = {
  key: string;
  name: string;
  myRole: ProjectRole | null;
  managers: ProjectMember[];
};

/** A note on how the project is going (GET /api/v1/projects/{key}/updates). */
export type ProjectUpdate = {
  id: string;
  health: ProjectHealth;
  body: string;
  author: IssuePerson;
  createdAt: string;
};
