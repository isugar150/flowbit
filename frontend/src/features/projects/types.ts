import type { RichDoc } from "@/components/editor/rich-text-editor";

export type ProjectRole = "MANAGER" | "EDITOR" | "VIEWER";

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
