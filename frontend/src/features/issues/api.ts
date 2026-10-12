import type { RichDoc } from "@/components/editor/rich-text-editor";
import { apiFetch } from "@/lib/api/client";
import type {
  Attachment,
  Issue,
  IssueActivityFeed,
  IssueChanges,
  IssueDocument,
  IssueDocumentSummary,
  Label,
  NewIssueInput,
  Reaction,
} from "./types";

export function createIssue(issue: NewIssueInput): Promise<Issue> {
  return apiFetch<Issue>("/api/v1/issues", { method: "POST", body: issue });
}

export function updateIssue(input: { number: number; changes: IssueChanges }): Promise<Issue> {
  return apiFetch<Issue>(`/api/v1/issues/${input.number}`, { method: "PATCH", body: input.changes });
}

/** Adds a workspace label; a name that already exists returns that label. */
export function createLabel(name: string): Promise<Label> {
  return apiFetch<Label>("/api/v1/labels", { method: "POST", body: { name } });
}

/** Follow an issue (to be told about its changes) or stop following it. */
export function setIssueSubscribed(input: { number: number; subscribed: boolean }): Promise<IssueActivityFeed> {
  return apiFetch<IssueActivityFeed>(`/api/v1/issues/${input.number}/subscription`, {
    method: input.subscribed ? "PUT" : "DELETE",
  });
}

/** Adds the signed-in person's emoji to the issue, or takes it back when it is already there. */
export function toggleReaction(input: { number: number; emoji: string }): Promise<Reaction[]> {
  return apiFetch<Reaction[]>(`/api/v1/issues/${input.number}/reactions`, { method: "POST", body: { emoji: input.emoji } });
}

export function uploadAttachment(input: { number: number; file: File }): Promise<Attachment> {
  const form = new FormData();
  form.append("file", input.file);
  return apiFetch<Attachment>(`/api/v1/issues/${input.number}/attachments`, { method: "POST", body: form });
}

export function deleteAttachment(id: string): Promise<void> {
  return apiFetch<void>(`/api/v1/attachments/${id}`, { method: "DELETE" });
}

export function createDocument(input: { number: number; title: string }): Promise<IssueDocumentSummary> {
  return apiFetch<IssueDocumentSummary>(`/api/v1/issues/${input.number}/documents`, {
    method: "POST",
    body: { title: input.title },
  });
}

export function updateDocument(input: { id: string; changes: { title?: string; content?: RichDoc | null } }): Promise<IssueDocument> {
  return apiFetch<IssueDocument>(`/api/v1/documents/${input.id}`, { method: "PATCH", body: input.changes });
}

export function deleteDocument(id: string): Promise<void> {
  return apiFetch<void>(`/api/v1/documents/${id}`, { method: "DELETE" });
}
