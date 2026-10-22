import type { MentionPerson } from "@/components/editor/mention";
import type { RichDoc } from "@/components/editor/rich-text-editor";
import type { IssuePerson, IssuePriority } from "@/features/issues/types";
import type { UserSummary } from "@/features/members/types";

export type ProjectRole = "MANAGER" | "EDITOR" | "VIEWER";
export type ProjectStatus = "BACKLOG" | "PLANNED" | "IN_PROGRESS" | "COMPLETED" | "CANCELED";
export type ProjectHealth = "ON_TRACK" | "AT_RISK" | "OFF_TRACK";

/** A project as the signed-in user sees it (GET /api/v1/projects, /api/v1/projects/{id}). */
export type Project = {
  id: string;
  name: string;
  /** The old one-line summary (D-134): still sent, no longer shown. */
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
  /** Health of the latest project update and when it was posted; null before the first update. */
  health: ProjectHealth | null;
  healthUpdatedAt: string | null;
  /** Whether the signed-in user follows the project: is told about every change to its issues. */
  subscribed: boolean;
  /** Whether the signed-in user was added to the project; a workspace administrator may enter every one without. */
  member: boolean;
  /** When the project was archived; null for a project in use. Only the archived list has it set. */
  archivedAt: string | null;
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
 * Everyone in the workspace in the shape of project members, for an issue that is in no project:
 * anyone can be assigned to it.
 */
/** The people "@" offers in an editor: whoever could be given the issue. */
export function mentionablesOf(members: ProjectMember[]): MentionPerson[] {
  return members.map((member) => ({
    id: member.userId,
    name: member.displayName ?? member.loginId,
    loginId: member.loginId,
    avatarUrl: member.avatarUrl,
  }));
}

export function usersAsMembers(users: UserSummary[] | null): ProjectMember[] {
  return (users ?? []).map((person) => ({ ...person, userId: person.id, role: "EDITOR" }));
}

/**
 * A project in the list (GET /api/v1/projects). Everyone sees every project; `myRole` is null
 * for one the signed-in user has not been added to, which they cannot enter.
 */
export type ProjectSummary = Omit<Project, "myRole"> & { myRole: ProjectRole | null };

/** Whether the signed-in user may enter a project, and whom to ask (GET /api/v1/projects/{id}/access). */
export type ProjectEntry = {
  id: string;
  name: string;
  myRole: ProjectRole | null;
  managers: ProjectMember[];
  /** When the signed-in user asked to be let in and is still waiting (D-128). */
  requestedAt: string | null;
};

/** Someone waiting to be let into a project (GET /api/v1/projects/{id}/access-requests). */
export type ProjectAccessRequest = {
  userId: string;
  loginId: string;
  displayName: string | null;
  avatarUrl: string | null;
  requestedAt: string;
};

/** A note on how the project is going (GET /api/v1/projects/{id}/updates). */
export type ProjectUpdate = {
  id: string;
  health: ProjectHealth;
  body: string;
  author: IssuePerson;
  createdAt: string;
};
