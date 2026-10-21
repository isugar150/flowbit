import type { RichDoc } from "@/components/editor/rich-text-editor";
import type { CommentApi } from "@/features/issues/comment-api";
import type { Attachment, IssueComment, Reaction } from "@/features/issues/types";
import { apiFetch, fileForm } from "@/lib/api/client";
import type { ContentFeed, ContentTarget, ContentThread } from "./types";

const base = (target: ContentTarget) => `/api/v1/content/${target.type}/${encodeURIComponent(target.id)}`;

export const contentFeedKey = (target: ContentTarget) => ["content-feed", target.type, target.id] as const;

export function getContentFeed(target: ContentTarget): Promise<ContentFeed> {
  return apiFetch<ContentFeed>(`${base(target)}/feed`);
}

/** Opens a thread on a block with its first comment. */
export function openThread(input: { target: ContentTarget; blockId: string; content: RichDoc }): Promise<ContentThread> {
  return apiFetch<ContentThread>(`${base(input.target)}/threads`, { method: "POST", body: { blockId: input.blockId, content: input.content } });
}

export function replyToThread(input: { threadId: string; content: RichDoc }): Promise<ContentThread> {
  return apiFetch<ContentThread>(`/api/v1/content-threads/${input.threadId}/comments`, { method: "POST", body: { content: input.content } });
}

export function editThreadComment(input: { id: string; content: RichDoc }): Promise<ContentThread> {
  return apiFetch<ContentThread>(`/api/v1/content-thread-comments/${input.id}`, { method: "PATCH", body: { content: input.content } });
}

/** The comment that opened a thread takes the thread with it. */
export function deleteThreadComment(id: string): Promise<void> {
  return apiFetch<void>(`/api/v1/content-thread-comments/${id}`, { method: "DELETE" });
}

export function resolveThread(threadId: string): Promise<ContentThread> {
  return apiFetch<ContentThread>(`/api/v1/content-threads/${threadId}/resolution`, { method: "POST" });
}

export function reopenThread(threadId: string): Promise<ContentThread> {
  return apiFetch<ContentThread>(`/api/v1/content-threads/${threadId}/resolution`, { method: "DELETE" });
}

/**
 * The general comments under the activity of a page (not an issue: it has its own), with
 * `changed` called after each change, so that the page's feed is read again.
 */
export function contentCommentApi(target: ContentTarget, changed: () => void): CommentApi {
  const then = async <T>(request: Promise<T>): Promise<T> => {
    const result = await request;
    changed();
    return result;
  };
  return {
    create: ({ content, attachmentIds }) =>
      then(apiFetch<IssueComment>(`${base(target)}/comments`, { method: "POST", body: { content, attachmentIds } })),
    update: ({ id, content, attachmentIds }) =>
      then(apiFetch<IssueComment>(`/api/v1/content-comments/${id}`, { method: "PATCH", body: { content, attachmentIds } })),
    remove: (id) => then(apiFetch<void>(`/api/v1/content-comments/${id}`, { method: "DELETE" })),
    react: ({ id, emoji }) => then(apiFetch<Reaction[]>(`/api/v1/content-comments/${id}/reactions`, { method: "POST", body: { emoji } })),
    uploadFile: (file) => apiFetch<Attachment>(`${base(target)}/comment-files`, { method: "POST", body: fileForm(file) }),
    discardFile: (id) => apiFetch<void>(`/api/v1/content-files/${id}`, { method: "DELETE" }),
  };
}

/** An emoji on a line of the activity of a page. */
export function reactToContentActivity(input: { id: string; emoji: string }): Promise<Reaction[]> {
  return apiFetch<Reaction[]>(`/api/v1/content-activities/${input.id}/reactions`, { method: "POST", body: { emoji: input.emoji } });
}
