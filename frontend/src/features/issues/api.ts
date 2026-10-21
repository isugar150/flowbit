import type { RichDoc } from "@/components/editor/rich-text-editor";
import { apiFetch, fileForm } from "@/lib/api/client";
import type {
  Attachment,
  Issue,
  IssueActivityFeed,
  IssueChanges,
  IssueComment,
  IssueDocument,
  IssueDocumentSummary,
  Label,
  NewIssueInput,
  NewSubIssueInput,
  Reaction,
} from "./types";

export function createIssue(issue: NewIssueInput | NewSubIssueInput): Promise<Issue> {
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

/**
 * Writes a comment; the people tagged in it with "@" are told through their notification channels.
 * `content` may be null when the comment is files alone; `attachmentIds` are files uploaded with
 * `uploadCommentFile` beforehand.
 */
export function createComment(input: {
  number: number;
  content: RichDoc | null;
  attachmentIds: string[];
  /** The comment this one answers (D-167). */
  parentId?: string;
}): Promise<IssueComment> {
  return apiFetch<IssueComment>(`/api/v1/issues/${input.number}/comments`, {
    method: "POST",
    body: { content: input.content, attachmentIds: input.attachmentIds, parentId: input.parentId },
  });
}

/** `attachmentIds` is the whole set of files the comment keeps; the ones left out are removed. */
export function updateComment(input: { id: string; content: RichDoc | null; attachmentIds: string[] }): Promise<IssueComment> {
  return apiFetch<IssueComment>(`/api/v1/comments/${input.id}`, {
    method: "PATCH",
    body: { content: input.content, attachmentIds: input.attachmentIds },
  });
}

/** Stores a file for a comment that is being written; it belongs to the comment once that is sent with its id. */
export function uploadCommentFile(input: { number: number; file: File }): Promise<Attachment> {
  return apiFetch<Attachment>(`/api/v1/issues/${input.number}/comment-files`, { method: "POST", body: fileForm(input.file) });
}

/** Puts the signed-in person's emoji on a comment, or takes it back when it is already there. */
export function toggleCommentReaction(input: { id: string; emoji: string }): Promise<Reaction[]> {
  return apiFetch<Reaction[]>(`/api/v1/comments/${input.id}/reactions`, { method: "POST", body: { emoji: input.emoji } });
}

/** The same for a line of the history. */
export function toggleActivityReaction(input: { id: string; emoji: string }): Promise<Reaction[]> {
  return apiFetch<Reaction[]>(`/api/v1/activities/${input.id}/reactions`, { method: "POST", body: { emoji: input.emoji } });
}

export function deleteComment(id: string): Promise<void> {
  return apiFetch<void>(`/api/v1/comments/${id}`, { method: "DELETE" });
}

/** Adds the signed-in person's emoji to the issue, or takes it back when it is already there. */
export function toggleReaction(input: { number: number; emoji: string }): Promise<Reaction[]> {
  return apiFetch<Reaction[]>(`/api/v1/issues/${input.number}/reactions`, { method: "POST", body: { emoji: input.emoji } });
}

export function uploadAttachment(input: { number: number; file: File }): Promise<Attachment> {
  return apiFetch<Attachment>(`/api/v1/issues/${input.number}/attachments`, { method: "POST", body: fileForm(input.file) });
}

export function deleteAttachment(id: string): Promise<void> {
  return apiFetch<void>(`/api/v1/attachments/${id}`, { method: "DELETE" });
}

/** Where a document is made: on an issue (by number), on a project (by id), or inside another document. */
export type DocumentOwner = { issueNumber: number } | { projectId: string } | { documentId: string };

export function createDocument(input: { owner: DocumentOwner; title: string }): Promise<IssueDocumentSummary> {
  const path =
    "issueNumber" in input.owner
      ? `/api/v1/issues/${input.owner.issueNumber}/documents`
      : "documentId" in input.owner
        ? `/api/v1/documents/${input.owner.documentId}/documents`
        : `/api/v1/projects/${encodeURIComponent(input.owner.projectId)}/documents`;
  return apiFetch<IssueDocumentSummary>(path, { method: "POST", body: { title: input.title } });
}

export function updateDocument(input: { id: string; changes: { title?: string; content?: RichDoc | null } }): Promise<IssueDocument> {
  return apiFetch<IssueDocument>(`/api/v1/documents/${input.id}`, { method: "PATCH", body: input.changes });
}

/** Deletes the document: it leaves where it was listed but is kept, and can be restored. */
export function deleteDocument(id: string): Promise<void> {
  return apiFetch<void>(`/api/v1/documents/${id}`, { method: "DELETE" });
}

export function restoreDocument(id: string): Promise<void> {
  return apiFetch<void>(`/api/v1/documents/${id}/restore`, { method: "POST" });
}

/**
 * The person has left the issue: what they changed on it goes out to its followers now, as one
 * notification, instead of after a minute of quiet (D-126).
 */
export function flushIssueNotifications(number: number): Promise<void> {
  return apiFetch<void>(`/api/v1/issues/${number}/notifications/flush`, { method: "POST" });
}
