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

/** Deletes the issue: it leaves the lists but can still be opened with its history, and restored. */
export function deleteIssue(number: number): Promise<void> {
  return apiFetch<void>(`/api/v1/issues/${number}`, { method: "DELETE" });
}

export function restoreIssue(number: number): Promise<Issue> {
  return apiFetch<Issue>(`/api/v1/issues/${number}/restore`, { method: "POST" });
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

/** Whose document: an issue (by number) or a project (by id). */
export type DocumentOwner = { issueNumber: number } | { projectId: string };

export function createDocument(input: { owner: DocumentOwner; title: string }): Promise<IssueDocumentSummary> {
  const path =
    "issueNumber" in input.owner
      ? `/api/v1/issues/${input.owner.issueNumber}/documents`
      : `/api/v1/projects/${encodeURIComponent(input.owner.projectId)}/documents`;
  return apiFetch<IssueDocumentSummary>(path, { method: "POST", body: { title: input.title } });
}

export function updateDocument(input: { id: string; changes: { title?: string; content?: RichDoc | null } }): Promise<IssueDocument> {
  return apiFetch<IssueDocument>(`/api/v1/documents/${input.id}`, { method: "PATCH", body: input.changes });
}

export function deleteDocument(id: string): Promise<void> {
  return apiFetch<void>(`/api/v1/documents/${id}`, { method: "DELETE" });
}
