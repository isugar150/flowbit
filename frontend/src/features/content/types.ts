import type { RichDoc } from "@/components/editor/rich-text-editor";
import type { IssueActivityItem, IssueComment, IssuePerson, Reaction } from "@/features/issues/types";

/** The four kinds of page whose blocks can be commented on: the word in the address (D-165). */
export type ContentTargetType = "project" | "issue" | "document" | "milestone";

export type ContentTarget = { type: ContentTargetType; id: string };

/** One comment of a thread on a block: text and tags only. */
export type ThreadComment = {
  id: string;
  author: IssuePerson;
  content: RichDoc;
  createdAt: string;
  /** Later than `createdAt` once it has been changed. */
  updatedAt: string;
  canEdit: boolean;
  canDelete: boolean;
};

/** The comments on one block of a page. */
export type ContentThread = {
  id: string;
  /** The block's number: `attrs.blockId` in the document. */
  blockId: string;
  /** The start of the block's text when the thread was opened; what it was about once the block is gone. */
  quote: string;
  /** The block is no longer in the page. */
  orphaned: boolean;
  resolved: boolean;
  resolvedAt: string | null;
  resolvedBy: IssuePerson | null;
  createdAt: string;
  createdBy: IssuePerson;
  comments: ThreadComment[];
};

export type ContentActivityType =
  | "NAME_CHANGED"
  | "CONTENT_EDITED"
  | "STATUS_CHANGED"
  | "PRIORITY_CHANGED"
  | "LEAD_CHANGED"
  | "DATES_CHANGED"
  | "TARGET_DATE_CHANGED"
  | "LABELS_CHANGED"
  | "ICON_CHANGED"
  | "ARCHIVED"
  | "RESTORED"
  | "MEMBER_ADDED"
  | "MEMBER_REMOVED"
  | "MEMBER_ROLE_CHANGED"
  | "UPDATE_POSTED"
  | "MILESTONE_ADDED"
  | "MILESTONE_REMOVED"
  | "DOCUMENT_ADDED"
  | "DOCUMENT_REMOVED"
  | "DOCUMENT_RESTORED";

/** A line of the activity of a project, a document or a milestone. */
export type ContentActivityItem = {
  id: string;
  type: ContentActivityType;
  detail: IssueActivityItem["detail"];
  actor: IssuePerson;
  createdAt: string;
  reactions: Reaction[];
};

/** GET /api/v1/content/{type}/{id}/feed */
export type ContentFeed = {
  /** Whether anything can still be added: not on a deleted issue or document. */
  canComment: boolean;
  readOnly: boolean;
  threads: ContentThread[];
  /** Empty for an issue, which keeps its own activity. */
  items: ContentActivityItem[];
  /** The general comments; empty for an issue. */
  comments: IssueComment[];
};
