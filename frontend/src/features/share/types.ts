import type { Issue } from "@/features/issues/types";

/** The kinds of page that can be shared (docs/features/sharing.md). */
export type ShareKind = "MY_ISSUES_CALENDAR";

/** What to share: a kind of page and the parameters that pick what of it is shown. The same target is always the same link. */
export type ShareTarget = {
  kind: ShareKind;
  /** `month` (2026-10) and `scope` (assigned, created) for the calendar of "내 티켓". */
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

/** What a link shows to anyone who opens it (GET /api/v1/public/shares/{token}). One shape for each kind. */
export type PublicShare = {
  kind: "MY_ISSUES_CALENDAR";
  /** The display name of the person who shared it; null when they have none. */
  sharedBy: string | null;
  params: Record<string, string>;
  content: { issues: SharedCalendarIssue[] };
};
