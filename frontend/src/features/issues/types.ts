import type { RichDoc } from "@/components/editor/rich-text-editor";

export type IssueStatus = "BACKLOG" | "READY" | "IN_PROGRESS" | "DONE" | "CANCELED";
export type IssuePriority = "NONE" | "URGENT" | "HIGH" | "MEDIUM" | "LOW";
export type IssuePreset = "BUG" | "ENHANCEMENT" | "FEATURE";

export type IssuePerson = {
  id: string;
  loginId: string;
  displayName: string | null;
  avatarUrl: string | null;
};

/** An issue as returned by /api/v1/projects/{key}/issues. */
export type Issue = {
  id: string;
  /** Project key and number, for example PAY-12. */
  key: string;
  number: number;
  title: string;
  /** Plain text of the body, derived by the server. */
  description: string;
  /** The body as an editor document. Only sent when a single issue is requested; null in lists. */
  content: RichDoc | null;
  preset: IssuePreset | null;
  status: IssueStatus;
  priority: IssuePriority;
  assignee: IssuePerson | null;
  /** YYYY-MM-DD */
  dueDate: string | null;
  createdBy: IssuePerson;
  version: number;
  createdAt: string;
  updatedAt: string;
};

export type NewIssueInput = {
  title: string;
  content: RichDoc | null;
  preset: IssuePreset | null;
  status: IssueStatus;
  priority: IssuePriority;
  assigneeId: string | null;
  dueDate: string | null;
};

/** Only the fields present are changed; null clears the assignee, due date or preset. */
export type IssueChanges = Partial<{
  title: string;
  content: RichDoc | null;
  preset: IssuePreset | null;
  status: IssueStatus;
  priority: IssuePriority;
  assigneeId: string | null;
  dueDate: string | null;
}>;
