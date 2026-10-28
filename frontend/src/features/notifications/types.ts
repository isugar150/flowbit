export type SmtpSecurity = "NONE" | "STARTTLS" | "SSL";

/** The mail part of GET /api/v1/admin/notifications. The password is write-only. */
export type SmtpSettings = {
  enabled: boolean;
  host: string;
  port: number;
  security: SmtpSecurity;
  username: string;
  passwordSet: boolean;
  fromAddress: string;
  fromName: string;
};

export type SmtpInput = Omit<SmtpSettings, "passwordSet"> & {
  password: string | null;
  testTo?: string | null;
};

/** The push part. The service account key is write-only. */
export type PushSettings = {
  enabled: boolean;
  serviceAccountSet: boolean;
  projectId: string | null;
  apiKey: string;
  messagingSenderId: string;
  appId: string;
  vapidKey: string;
};

export type PushInput = Omit<
  PushSettings,
  "serviceAccountSet" | "projectId"
> & { serviceAccount: string | null };

export type NotificationSettings = { smtp: SmtpSettings; push: PushSettings };

export const WEBHOOK_EVENT_TYPES = [
  "CREATED",
  "TRIAGE_REQUESTED",
  "AUTOMATION_TRIGGERED",
  "TITLE_CHANGED",
  "DESCRIPTION_CHANGED",
  "STATUS_CHANGED",
  "PRIORITY_CHANGED",
  "ASSIGNEE_CHANGED",
  "DUE_DATE_CHANGED",
  "LABELS_CHANGED",
  "PROJECT_CHANGED",
  "PARENT_CHANGED",
  "ATTACHMENT_ADDED",
  "ATTACHMENT_REMOVED",
  "DOCUMENT_ADDED",
  "DOCUMENT_REMOVED",
  "DOCUMENT_RESTORED",
  "DELETED",
  "RESTORED",
  "COMMENT_ADDED",
  "MENTIONED",
  "ACCESS_REQUESTED",
  "DAILY_SUMMARY",
  "GIT_BRANCH_LINKED",
  "GIT_PR_LINKED",
  "GIT_PR_MERGED",
  "GIT_PR_CLOSED",
  "GIT_COMMITS_PUSHED",
] as const;

/** Sent only to API notifications that choose them, and offered only while Git is in use (D-141). */
export const GIT_EVENT_TYPES: readonly WebhookEventType[] = ["GIT_BRANCH_LINKED", "GIT_PR_LINKED", "GIT_PR_MERGED", "GIT_PR_CLOSED", "GIT_COMMITS_PUSHED"];

export type WebhookEventType = (typeof WEBHOOK_EVENT_TYPES)[number];
export type WebhookMethod = "GET" | "POST" | "PUT" | "PATCH" | "DELETE";

/**
 * An outgoing API call, made once for each person a change is told to
 * (GET /api/v1/admin/notifications/webhooks). The signing secret is write-only.
 */
export type Webhook = {
  id: string;
  name: string;
  enabled: boolean;
  url: string;
  method: WebhookMethod;
  contentType: string;
  headers: Record<string, string>;
  bodyTemplate: string;
  signingSecretSet: boolean;
  eventTypes: WebhookEventType[];
  lastStatus: number | null;
  lastError: string | null;
  lastAttemptAt: string | null;
};

export type WebhookInput = Omit<
  Webhook,
  "id" | "signingSecretSet" | "lastStatus" | "lastError" | "lastAttemptAt"
> & {
  signingSecret: string | null;
  /** Test only: the saved webhook whose signing secret to use. */
  id?: string;
};

export type TestResult = { ok: boolean; message: string };

/** The Firebase web app's public configuration, handed out when push is switched on. */
export type PushClientConfig = {
  apiKey: string;
  projectId: string;
  messagingSenderId: string;
  appId: string;
  vapidKey: string;
};

/** GET /api/v1/me/notifications */
export type MyNotificationSettings = {
  email: string | null;
  notifyEmail: boolean;
  mailAvailable: boolean;
  push: PushClientConfig | null;
};

/** One notification in a person's inbox (GET /api/v1/me/inbox), D-216. */
export type InboxItem = {
  id: string;
  title: string;
  headline: string;
  /** One line per change, as the mail has them. */
  lines: string[];
  /** Where it opens in the app. */
  path: string;
  createdAt: string;
  read: boolean;
};

export type Inbox = {
  items: InboxItem[];
  /** Of all of them, not only the ones listed. */
  unreadCount: number;
};
