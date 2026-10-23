import type { Issue } from "@/features/issues/types";

/** The kinds of page that can be shared (docs/features/sharing.md). */
export type ShareKind = "MY_ISSUES_CALENDAR" | "MY_ISSUES_TIMELINE";

/** What to share: a kind of page and the parameters that pick what of it is shown. The same target is always the same link. */
export type ShareTarget = {
  kind: ShareKind;
  /**
   * For "내 티켓": `scope` (assigned, created) and, for the calendar, `month` (2026-10), for the timeline, `from` and `to`
   * (the months it covers, the last included).
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

/** One time a link was opened. */
export type ShareAccess = {
  accessedAt: string;
  ipAddress: string | null;
  userAgent: string | null;
  /** Opened inside a frame of another page. */
  embedded: boolean;
  /** That page, as the shared page itself reported it. */
  referrer: string | null;
};

export type ShareAccesses = { total: number; items: ShareAccess[] };

/** An issue as the shared calendar shows it: where it is and what it is, nothing else. */
export type SharedCalendarIssue = Pick<Issue, "number" | "key" | "title" | "status" | "startDate"> & { dueDate: string };

/** An issue as the shared timeline draws it, with the name of its project, which the chart groups by. */
export type SharedTimelineIssue = Pick<Issue, "number" | "key" | "title" | "status" | "startDate" | "dueDate" | "createdAt" | "updatedAt"> & {
  project: string | null;
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
  | PublicShareOf<"MY_ISSUES_TIMELINE", { issues: SharedTimelineIssue[] }>;
