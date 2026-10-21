import type { RichDoc } from "@/components/editor/rich-text-editor";
import { createComment, deleteAttachment, deleteComment, toggleCommentReaction, updateComment, uploadCommentFile } from "./api";
import type { Attachment, Reaction } from "./types";

/**
 * What the comment components do to the server. An issue's comments and the comments under the
 * activity of a project, a document or a milestone (D-165) look and behave alike, so the same
 * components serve both; each page gives them its own calls.
 */
export type CommentApi = {
  /** Sends a comment; `content` is null when it is files alone. `parentId` makes it a reply to that comment (D-167). */
  create(input: { content: RichDoc | null; attachmentIds: string[]; parentId?: string }): Promise<unknown>;
  /** `attachmentIds` is the whole set of files the comment keeps. */
  update(input: { id: string; content: RichDoc | null; attachmentIds: string[] }): Promise<unknown>;
  remove(id: string): Promise<void>;
  /** Puts the signed-in person's emoji on the comment, or takes it back. */
  react(input: { id: string; emoji: string }): Promise<Reaction[]>;
  /** Stores a file for a comment being written. */
  uploadFile(file: File): Promise<Attachment>;
  /** Takes back a file chosen for a comment that was never sent. */
  discardFile(id: string): Promise<void>;
};

/** The comments of one issue, by its number. */
export function issueCommentApi(number: number): CommentApi {
  return {
    create: ({ content, attachmentIds, parentId }) => createComment({ number, content, attachmentIds, parentId }),
    update: updateComment,
    remove: deleteComment,
    react: toggleCommentReaction,
    uploadFile: (file) => uploadCommentFile({ number, file }),
    discardFile: deleteAttachment,
  };
}
