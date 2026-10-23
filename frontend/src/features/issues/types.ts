import type { RichDoc } from "@/components/editor/rich-text-editor";
import type { NewCustomPropertyInput } from "@/features/custom-properties/types";
import type { IssueGitLink, IssueGitRepository } from "@/features/git/types";

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

/** One emoji on an issue, a comment or a line of the history. */
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
  /** When it was deleted (archived); null for a document in use. */
  deletedAt: string | null;
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
  /** Whether the person may bring this deleted document back. A deleted document is read-only. */
  canRestore: boolean;
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
  /** In the order they were given the issue; empty when no one has it (D-125). */
  assignees: IssuePerson[];
  /** YYYY-MM-DD. The first day when the issue covers a span that ends on `dueDate`; null for a single date. */
  startDate: string | null;
  /** YYYY-MM-DD */
  dueDate: string | null;
  /** The project the issue is linked to; null when it is on its own. */
  project: { id: string; name: string } | null;
  /** The issue this one is a sub-issue of. */
  parent: { number: number; key: string; title: string } | null;
  /** Every issue above this one, the top-level one first and `parent` last; empty for a top-level issue. */
  ancestors: { number: number; key: string; title: string }[];
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
  /** When a done or canceled issue was finished; null while it is open. */
  completedAt: string | null;
};

/** One issue with what its page shows (GET /api/v1/issues/{number}). */
export type IssueDetail = Issue & {
  /** Never true for a deleted issue. */
  canEdit: boolean;
  /** On a deleted issue: whether the signed-in person may restore it. */
  canRestore?: boolean;
  /** False when the signed-in person may not edit, or the issue is at the deepest level of sub-issues. */
  canAddSubIssue: boolean;
  subIssues: Issue[];
  reactions: Reaction[];
  attachments: Attachment[];
  documents: IssueDocumentSummary[];
  /** Pull requests and branches a Git server linked the issue to; empty without Git (D-141). */
  git?: IssueGitLink[];
  /** The repository picked on the issue (D-148); absent when none. */
  gitRepository?: IssueGitRepository;
  /** The repositories of its project it can be picked from; empty for an issue on its own. */
  gitRepositoryOptions?: IssueGitRepository[];
};

export type NewIssueInput = {
  title: string;
  content: RichDoc | null;
  status: IssueStatus;
  priority: IssuePriority;
  /** Up to ten people, in order. */
  assigneeIds: string[];
  /** Only together with `dueDate`, and never after it. */
  startDate: string | null;
  dueDate: string | null;
  labelIds: string[];
  /** The project to link the issue to; null leaves it on its own. */
  projectId: string | null;
  /** Makes the issue a sub-issue of this one. */
  parentId?: string;
  /** Properties to give the issue as it is made, in order (D-193); left out for none. */
  customProperties?: NewCustomPropertyInput[];
};

/**
 * A sub-issue made under an issue (D-129): its project is always its parent's,
 * and without `assigneeIds` it starts with its parent's assignees.
 */
export type NewSubIssueInput = Omit<NewIssueInput, "projectId" | "assigneeIds" | "parentId"> & {
  parentId: string;
  assigneeIds?: string[];
};

/** Only the fields present are changed; null clears the due date. `labelIds` and `assigneeIds` replace all labels and assignees. */
export type IssueChanges = Partial<{
  title: string;
  content: RichDoc | null;
  status: IssueStatus;
  priority: IssuePriority;
  assigneeIds: string[];
  /** Only together with `dueDate`, and never after it. */
  startDate: string | null;
  dueDate: string | null;
  labelIds: string[];
  /** Links the issue to another project, or to none with null. */
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
  | "LABELS_CHANGED"
  | "PROJECT_CHANGED"
  | "PARENT_CHANGED"
  | "CUSTOM_PROPERTY_CHANGED"
  | "ATTACHMENT_ADDED"
  | "ATTACHMENT_REMOVED"
  | "DOCUMENT_ADDED"
  | "DOCUMENT_REMOVED"
  | "DOCUMENT_RESTORED"
  | "DELETED"
  | "RESTORED"
  | "GIT_BRANCH_LINKED"
  | "GIT_PR_LINKED"
  | "GIT_PR_MERGED"
  | "GIT_PR_CLOSED"
  | "GIT_COMMITS_PUSHED"
  | "GIT_UNLINKED";

/**
 * Set on an entry a Git server made: which kind of server (D-153; missing on older entries) and
 * who it was there (null when the event named nobody, such as a branch read from the GitHub API).
 */
export type ActivityVia = { source: "git"; provider?: string; name?: string | null };

export type IssueActivityItem = {
  id: string;
  type: IssueActivityType;
  /**
   * `{from, to}` for most types (names as they were then), `{added, removed}` for labels. Git
   * entries hold what they link (`repository`, `branch`, `number`, `title`, `url`, `count`,
   * `commits`) and `via`.
   */
  detail: { from?: unknown; to?: unknown; added?: unknown; removed?: unknown; via?: ActivityVia; [key: string]: unknown };
  /** Null when a Git server made the change for someone without an account here; `detail.via` names them. */
  actor: IssuePerson | null;
  createdAt: string;
  /** The emoji on this line of the history (D-164). */
  reactions: Reaction[];
};

/** How many files one comment can hold (IssueService.MAX_COMMENT_FILES). */
export const MAX_COMMENT_FILES = 10;

/** A comment on an issue. */
export type IssueComment = {
  id: string;
  content: RichDoc;
  author: IssuePerson;
  createdAt: string;
  /** Later than `createdAt` once the comment has been edited. */
  updatedAt: string;
  /** Whether the signed-in person may change it (its author). */
  canEdit: boolean;
  /** Whether the signed-in person may delete it (its author or a workspace admin). */
  canDelete: boolean;
  reactions: Reaction[];
  /** The files sent with it, in the order they were added. */
  attachments: Attachment[];
  /** The comment this one answers; null for a comment of its own. A reply cannot be answered (D-167). */
  parentId: string | null;
  /** Deleted but kept because it has replies: no text, files or emoji, and shown as "삭제된 댓글" (D-167). */
  deleted: boolean;
};

/** GET /api/v1/issues/{number}/activity */
export type IssueActivityFeed = {
  items: IssueActivityItem[];
  /** Oldest first, replies among them; shown mixed into `items` by time, each reply under its comment. */
  comments: IssueComment[];
  subscribers: IssuePerson[];
  /** Whether the signed-in person follows the issue. */
  subscribed: boolean;
};
