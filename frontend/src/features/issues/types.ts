import type { RichDoc } from "@/components/editor/rich-text-editor";

export type IssueStatus = "BACKLOG" | "READY" | "IN_PROGRESS" | "DONE" | "CANCELED";
export type IssuePriority = "NONE" | "URGENT" | "HIGH" | "MEDIUM" | "LOW";

/** A workspace label (GET /api/v1/labels). */
export type Label = {
  id: string;
  name: string;
  /** #rrggbb */
  color: string;
};

export type IssuePerson = {
  id: string;
  loginId: string;
  displayName: string | null;
  avatarUrl: string | null;
};

/** One emoji on an issue. */
export type Reaction = {
  emoji: string;
  count: number;
  /** Whether the signed-in person is one of those who chose it. */
  reacted: boolean;
  names: string[];
};

export type Attachment = {
  id: string;
  fileName: string;
  sizeBytes: number;
  /** Where to download it from. */
  url: string;
  createdAt: string;
};

/** A document as listed on the issue or the project it belongs to. */
export type IssueDocumentSummary = {
  id: string;
  title: string;
  updatedAt: string;
};

/** GET /api/v1/documents/{id} */
export type IssueDocument = IssueDocumentSummary & {
  content: RichDoc | null;
  /** The issue the document belongs to, or null when it belongs to a project. */
  issue: { number: number; key: string; title: string } | null;
  /** The project the document belongs to, or null when it belongs to an issue. */
  project: { id: string; name: string } | null;
  canEdit: boolean;
  /** The documents it sits inside, the outermost first. */
  ancestors: IssueDocumentSummary[];
  /** The documents inside it (page blocks in its body). */
  children: IssueDocumentSummary[];
  /** Whether a document may be made inside it: project documents only, five levels deep at most. */
  canAddChild: boolean;
};

/** An issue as returned by /api/v1/issues. */
export type Issue = {
  id: string;
  /** The workspace's issue key and the issue's number, for example FB-12. */
  key: string;
  number: number;
  title: string;
  /** Plain text of the body, derived by the server. */
  description: string;
  /** The body as an editor document. Only sent when a single issue is requested; null in lists. */
  content: RichDoc | null;
  status: IssueStatus;
  priority: IssuePriority;
  assignee: IssuePerson | null;
  /** YYYY-MM-DD. The first day when the issue covers a span that ends on `dueDate`; null for a single date. */
  startDate: string | null;
  /** YYYY-MM-DD */
  dueDate: string | null;
  /** The project the issue is linked to; null when it is on its own. */
  project: { id: string; name: string } | null;
  milestone: { id: string; name: string; /** YYYY-MM-DD */ targetDate?: string | null } | null;
  /** The issue this one is a sub-issue of. */
  parent: { number: number; key: string; title: string } | null;
  labels: Label[];
  /** How many sub-issues the issue has, and how many of them are done. */
  subIssueCount: number;
  subIssueDoneCount: number;
  createdBy: IssuePerson;
  version: number;
  createdAt: string;
  updatedAt: string;
  /** When the issue was deleted; null for an issue in use. */
  deletedAt: string | null;
};

/** One issue with what its page shows (GET /api/v1/issues/{number}). */
export type IssueDetail = Issue & {
  /** Never true for a deleted issue. */
  canEdit: boolean;
  /** On a deleted issue: whether the signed-in person may restore it. */
  canRestore?: boolean;
  subIssues: Issue[];
  reactions: Reaction[];
  attachments: Attachment[];
  documents: IssueDocumentSummary[];
};

export type NewIssueInput = {
  title: string;
  content: RichDoc | null;
  status: IssueStatus;
  priority: IssuePriority;
  assigneeId: string | null;
  /** Only together with `dueDate`, and never after it. */
  startDate: string | null;
  dueDate: string | null;
  milestoneId: string | null;
  labelIds: string[];
  /** The project to link the issue to; null leaves it on its own. */
  projectId: string | null;
  /** Makes the issue a sub-issue of this one. */
  parentId?: string;
};

/** Only the fields present are changed; null clears the assignee, due date or milestone. `labelIds` replaces all labels. */
export type IssueChanges = Partial<{
  title: string;
  content: RichDoc | null;
  status: IssueStatus;
  priority: IssuePriority;
  assigneeId: string | null;
  /** Only together with `dueDate`, and never after it. */
  startDate: string | null;
  dueDate: string | null;
  milestoneId: string | null;
  labelIds: string[];
  /** Links the issue to another project, or to none with null. Its milestone is dropped. */
  projectId: string | null;
  parentId: string | null;
}>;

/** The emoji an issue can be reacted to with (IssueService.REACTIONS). */
export const REACTIONS = ["👍", "❤️", "🎉", "😄", "👀", "🚀", "🙏", "✅"] as const;

export type IssueActivityType =
  | "CREATED"
  | "TITLE_CHANGED"
  | "DESCRIPTION_CHANGED"
  | "STATUS_CHANGED"
  | "PRIORITY_CHANGED"
  | "ASSIGNEE_CHANGED"
  | "DUE_DATE_CHANGED"
  | "MILESTONE_CHANGED"
  | "LABELS_CHANGED"
  | "PROJECT_CHANGED"
  | "PARENT_CHANGED"
  | "ATTACHMENT_ADDED"
  | "ATTACHMENT_REMOVED"
  | "DOCUMENT_ADDED"
  | "DOCUMENT_REMOVED"
  | "DELETED"
  | "RESTORED";

export type IssueActivityItem = {
  id: string;
  type: IssueActivityType;
  /** `{from, to}` for most types (names as they were then), `{added, removed}` for labels. */
  detail: { from?: unknown; to?: unknown; added?: unknown; removed?: unknown };
  actor: IssuePerson;
  createdAt: string;
};

/** GET /api/v1/issues/{number}/activity */
export type IssueActivityFeed = {
  items: IssueActivityItem[];
  subscribers: IssuePerson[];
  /** Whether the signed-in person follows the issue. */
  subscribed: boolean;
};
