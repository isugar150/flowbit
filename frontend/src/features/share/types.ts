import type { RichDoc } from "@/components/editor/rich-text-editor";
import type { Issue } from "@/features/issues/types";

/** The kinds of page that can be shared (docs/features/sharing.md). */
export type ShareKind = "MY_ISSUES_CALENDAR" | "MY_ISSUES_TIMELINE" | "ISSUE";

/** What to share: a kind of page and the parameters that pick what of it is shown. The same target is always the same link. */
export type ShareTarget = {
  kind: ShareKind;
  /**
   * For "내 티켓": `scope` (assigned, created) and, for the calendar, `month` (2026-10), for the timeline, `from` and `to`
   * (the months it covers, the last included); for an issue, `number`.
   */
  params: Record<string, string>;
};

/** A link as its owner sees it (POST /api/v1/shares). */
export type ShareLink = {
  id: string;
  kind: ShareKind;
  params: Record<string, string>;
  token: string;
  /** Where it opens on this site: /s/{token}. */
  path: string;
  createdAt: string;
};

/** A link in the person's list of their links (GET /api/v1/shares, D-202). */
export type ShareSummary = ShareLink & {
  /** What it is about when its parameters do not say it (an issue: "FB-12 제목"); null otherwise. */
  subject: string | null;
};

/** A page of the person's links (GET /api/v1/shares, D-204): how many there are of the kind asked for, how many of each kind, and the page. */
export type ShareList = {
  total: number;
  kinds: Partial<Record<ShareKind, number>>;
  items: ShareSummary[];
};

/**
 * An issue as the shared calendar shows it: where it is and what it is, and for the card over it its priority, labels,
 * project and who made it. No history (D-214).
 */
export type SharedCalendarIssue = Pick<Issue, "number" | "key" | "title" | "status" | "priority" | "startDate"> & {
  dueDate: string;
  project: string | null;
  createdBy: string | null;
  labels: { name: string; color: string }[];
};

/** An issue as the shared timeline draws it, with the name of its project, which the chart groups by. */
export type SharedTimelineIssue = Pick<Issue, "number" | "key" | "title" | "status" | "startDate" | "dueDate" | "createdAt" | "updatedAt"> & {
  project: string | null;
};

/** An issue named by where it stands, for the path at the top of a shared page. */
export type SharedIssueRef = { number: number; key: string; title: string };

/** A sub-issue in the list of a shared issue. */
export type SharedSubIssue = Pick<Issue, "number" | "key" | "title" | "status" | "priority" | "dueDate" | "subIssueCount" | "subIssueDoneCount">;

export type SharedDocumentRef = { id: string; title: string; updatedAt: string };

/** A shared issue, or a sub-issue of it, as its page shows it: read-only, with no comments or history (docs/features/sharing.md). */
export type SharedIssue = Pick<Issue, "number" | "key" | "title" | "status" | "priority" | "startDate" | "dueDate" | "createdAt" | "updatedAt"> & {
  type: "issue";
  /** The number of the issue the link shares: the top of every path. */
  rootNumber: number;
  project: string | null;
  labels: { name: string; color: string }[];
  /** Display names only. */
  assignees: string[];
  createdBy: string | null;
  /** The body, with the addresses of its pictures and files already turned into ones that work inside the link. */
  content: RichDoc | null;
  /** The issues from the shared one down to this one's parent; empty on the shared one. */
  trail: SharedIssueRef[];
  subIssues: SharedSubIssue[];
  documents: SharedDocumentRef[];
};

/** A document of a shared issue or of one of its sub-issues. */
export type SharedDocument = {
  type: "document";
  rootNumber: number;
  id: string;
  title: string;
  updatedAt: string;
  content: RichDoc | null;
  issue: SharedIssueRef;
  /** From the shared issue down to the one the document belongs to, that one included. */
  trail: SharedIssueRef[];
  /** The documents of that issue, for the titles of the document blocks in the text. */
  documents: SharedDocumentRef[];
};

type PublicShareOf<K extends ShareKind, Content> = {
  kind: K;
  /** The display name of the person who shared it; null when they have none. */
  sharedBy: string | null;
  params: Record<string, string>;
  content: Content;
};

/** What a link shows to anyone who opens it (GET /api/v1/public/shares/{token}). One shape for each kind. */
export type PublicShare =
  | PublicShareOf<"MY_ISSUES_CALENDAR", { issues: SharedCalendarIssue[] }>
  | PublicShareOf<"MY_ISSUES_TIMELINE", { issues: SharedTimelineIssue[] }>
  | PublicShareOf<"ISSUE", SharedIssue | SharedDocument>;
