import { apiFetch } from "@/lib/api/client";
import type {
  Inbox,
  MyNotificationSettings,
  NotificationSettings,
  PushInput,
  SmtpInput,
  TestResult,
  Webhook,
  WebhookInput,
} from "./types";

const ADMIN = "/api/v1/admin/notifications";

export function saveSmtp(input: SmtpInput): Promise<NotificationSettings> {
  return apiFetch<NotificationSettings>(`${ADMIN}/smtp`, {
    method: "PUT",
    body: input,
  });
}

/** Sends one mail with these settings without saving them. */
export function testSmtp(input: SmtpInput): Promise<TestResult> {
  return apiFetch<TestResult>(`${ADMIN}/smtp/test`, {
    method: "POST",
    body: input,
  });
}

export function savePush(input: PushInput): Promise<NotificationSettings> {
  return apiFetch<NotificationSettings>(`${ADMIN}/push`, {
    method: "PUT",
    body: input,
  });
}

/** Pushes a test message to the browsers this administrator registered. */
export function testPush(): Promise<TestResult> {
  return apiFetch<TestResult>(`${ADMIN}/push/test`, { method: "POST" });
}

export function createWebhook(input: WebhookInput): Promise<Webhook> {
  return apiFetch<Webhook>(`${ADMIN}/webhooks`, {
    method: "POST",
    body: input,
  });
}

export function updateWebhook({
  id,
  input,
}: {
  id: string;
  input: WebhookInput;
}): Promise<Webhook> {
  return apiFetch<Webhook>(`${ADMIN}/webhooks/${id}`, {
    method: "PUT",
    body: input,
  });
}

export function deleteWebhook(id: string): Promise<void> {
  return apiFetch<void>(`${ADMIN}/webhooks/${id}`, { method: "DELETE" });
}

/** Calls the address once with a made-up issue change, without saving anything. */
export function testWebhook(input: WebhookInput): Promise<TestResult> {
  return apiFetch<TestResult>(`${ADMIN}/webhooks/test`, {
    method: "POST",
    body: input,
  });
}

export function saveMyNotifications(input: {
  email: string;
  notifyEmail: boolean;
}): Promise<MyNotificationSettings> {
  return apiFetch<MyNotificationSettings>("/api/v1/me/notifications", {
    method: "PATCH",
    body: input,
  });
}

export function registerPushToken(token: string): Promise<void> {
  return apiFetch<void>("/api/v1/me/push-tokens", {
    method: "POST",
    body: { token },
  });
}

export function unregisterPushToken(token: string): Promise<void> {
  return apiFetch<void>("/api/v1/me/push-tokens/unregister", {
    method: "POST",
    body: { token },
  });
}

/** The latest notifications of the signed-in person and how many of all of them are not read yet (D-216). */
export function fetchInbox(): Promise<Inbox> {
  return apiFetch<Inbox>("/api/v1/me/inbox?limit=30");
}

/** The person opened the page `path` (`/issue/FB-12`): what they were told about it is read (D-217). `marked` is how many were unread. */
export function markInboxPathRead(path: string): Promise<{ marked: number }> {
  return apiFetch<{ marked: number }>("/api/v1/me/inbox/read-path", { method: "POST", body: { path } });
}

/** Marks these notifications read, or all of them. */
export function markInboxRead(input: { ids: string[] } | { all: true }): Promise<void> {
  return apiFetch<void>("/api/v1/me/inbox/read", { method: "POST", body: input });
}
