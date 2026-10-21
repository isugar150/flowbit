"use client";

import { useRef, useState } from "react";
import { PencilIcon, Trash2Icon } from "lucide-react";
import { TimeAgo } from "@/components/dates";
import type { MentionPerson } from "@/components/editor/mention";
import { RichTextEditor, type RichDoc, type RichTextEditorHandle } from "@/components/editor/rich-text-editor";
import { ModKey } from "@/components/mod-key";
import { PersonAvatar, personName } from "@/components/person";
import { Button } from "@/components/ui/button";
import { Spinner } from "@/components/ui/spinner";
import { apiErrorMessage } from "@/lib/api/error-message";
import { useRefreshingMutation } from "@/lib/use-refreshing-mutation";
import { cn } from "@/lib/utils";
import { messages } from "@/messages";
import { createComment, deleteComment, toggleCommentReaction, updateComment } from "../api";
import type { IssueComment } from "../types";
import { AttachButton, CommentFiles, DraftFiles, splitFiles, useCommentFiles, useFileTransfer } from "./comment-files";
import { ReactionPicker, ReactionPills, useReactions } from "./issue-reactions";

const m = messages.issues.comments;

/** Ctrl/⌘+Enter sends; caught before the editor, which would break the line instead. */
function takeSubmitKey(event: React.KeyboardEvent): boolean {
  if (event.key !== "Enter" || !(event.ctrlKey || event.metaKey) || event.nativeEvent.isComposing) return false;
  event.preventDefault();
  event.stopPropagation();
  return true;
}

/** Whether a document holds nothing a reader would see: only empty paragraphs. A comment of files alone has such a text. */
function isBlankDoc(node: unknown): boolean {
  if (typeof node !== "object" || node === null) return true;
  const { type, text, content } = node as { type?: string; text?: string; content?: unknown[] };
  if (type === "text") return !text?.trim();
  if (type !== "doc" && type !== "paragraph" && type !== "hardBreak") return false;
  return (content ?? []).every(isBlankDoc);
}

/**
 * Where a comment is written, under the activity of an issue. The same editor as the body, so `/`
 * blocks and pasted pictures work; `@` tags a person, who is then told through every notification
 * channel they have set up and follows the issue from then on. Pictures, and other files (up to
 * ten) from the paperclip, a drop or a paste, are sent along: pictures inside the text, the rest
 * as files under it.
 */
export function CommentComposer({ number, mentionables }: { number: number; mentionables: MentionPerson[] }) {
  const draft = useRef<RichDoc | null>(null);
  const editor = useRef<RichTextEditorHandle>(null);
  const [empty, setEmpty] = useState(true);
  // A new key empties the editor after the comment is sent.
  const [revision, setRevision] = useState(0);
  const files = useCommentFiles(number);
  const mutation = useRefreshingMutation(createComment, () => {
    draft.current = null;
    setEmpty(true);
    setRevision((current) => current + 1);
    files.clear();
  });
  const sendable = (!empty || files.ids.length > 0) && !files.busy && !mutation.isPending;
  const submit = () => {
    if (sendable) mutation.mutate({ number, content: empty ? null : draft.current, attachmentIds: files.ids });
  };
  const takeFiles = (list: File[]) => {
    const { pictures, others } = splitFiles(list);
    if (pictures.length > 0) void editor.current?.insertImages(pictures);
    if (others.length > 0) void files.add(others);
  };
  const transfer = useFileTransfer(takeFiles);

  return (
    <div
      data-presence-id="comment"
      className={cn(
        "flex flex-col gap-2 rounded-lg border p-3 focus-within:border-ring focus-within:ring-3 focus-within:ring-ring/50",
        transfer.dragging && "border-ring ring-3 ring-ring/50",
      )}
      onKeyDownCapture={(event) => {
        if (takeSubmitKey(event)) submit();
      }}
      {...transfer.props}
    >
      <RichTextEditor
        key={revision}
        ref={editor}
        initialContent={null}
        placeholder={m.placeholder}
        ariaLabel={m.label}
        className="min-h-14 text-sm"
        mentionables={mentionables}
        onChange={(doc) => {
          draft.current = doc;
          setEmpty(doc === null || isBlankDoc(doc));
        }}
      />
      {transfer.dragging ? (
        <p className="text-[0.8125rem] leading-5 text-muted-foreground" role="status">
          {m.dropFiles}
        </p>
      ) : null}
      <DraftFiles files={files.files} onRemove={files.remove} />
      {files.error || mutation.isError ? (
        <p role="alert" className="text-[0.8125rem] leading-5 text-destructive">
          {files.error ?? apiErrorMessage(mutation.error)}
        </p>
      ) : null}
      <div className="flex items-center gap-2">
        <AttachButton onFiles={takeFiles} disabled={mutation.isPending} />
        <span className="ml-auto text-[0.75rem] leading-4 text-muted-foreground max-md:hidden" aria-hidden="true">
          <ModKey />
          +Enter
        </span>
        <Button type="button" size="sm" disabled={!sendable} className="max-md:h-11" onClick={submit}>
          {mutation.isPending ? <Spinner data-icon="inline-start" aria-hidden="true" /> : null}
          {mutation.isPending ? m.submitting : m.submit}
        </Button>
      </div>
    </div>
  );
}

/**
 * One comment in the activity of an issue. Its author can change it in place; they or an admin can
 * delete it. Anyone who sees it can put an emoji on it (`readOnly`, a deleted issue, only shows them).
 */
export function CommentItem({
  number,
  comment,
  mentionables,
  readOnly = false,
}: {
  /** The issue the comment is on, where files added to it are uploaded. */
  number: number;
  comment: IssueComment;
  mentionables: MentionPerson[];
  readOnly?: boolean;
}) {
  const [editing, setEditing] = useState(false);
  const draft = useRef<RichDoc | null>(comment.content);
  const editor = useRef<RichTextEditorHandle>(null);
  // A new key puts the stored comment back when editing starts or is given up.
  const [revision, setRevision] = useState(0);
  const [empty, setEmpty] = useState(false);
  // The first press asks, the second one deletes (the same as deleting an issue).
  const [confirming, setConfirming] = useState(false);
  const files = useCommentFiles(number, comment.attachments);
  const reactions = useReactions(comment.reactions, (emoji) => toggleCommentReaction({ id: comment.id, emoji }));
  const stopEditing = () => {
    setEditing(false);
    setRevision((current) => current + 1);
  };
  const saving = useRefreshingMutation(updateComment, stopEditing);
  const removal = useRefreshingMutation(deleteComment);
  const savable = (!empty || files.ids.length > 0) && !files.busy && !saving.isPending;
  const save = () => {
    if (savable) saving.mutate({ id: comment.id, content: empty ? null : draft.current, attachmentIds: files.ids });
  };
  const takeFiles = (list: File[]) => {
    const { pictures, others } = splitFiles(list);
    if (pictures.length > 0) void editor.current?.insertImages(pictures);
    if (others.length > 0) void files.add(others);
  };
  const transfer = useFileTransfer(takeFiles);
  const failure = saving.error ?? removal.error;
  const error = files.error ?? (failure ? apiErrorMessage(failure) : null);
  // A comment of files alone has no text to show.
  const textless = isBlankDoc(comment.content) && comment.attachments.length > 0;

  return (
    <li className="flex flex-col gap-2 rounded-lg border p-3">
      <div className="flex min-h-7 flex-wrap items-center gap-x-2 gap-y-1 text-[0.8125rem] leading-5">
        <PersonAvatar person={comment.author} />
        <span className="min-w-0 truncate font-medium">{personName(comment.author)}</span>
        <span className="text-muted-foreground">
          <TimeAgo iso={comment.createdAt} />
          {comment.updatedAt !== comment.createdAt ? ` · ${m.edited}` : null}
        </span>
        {!editing && (comment.canEdit || comment.canDelete || !readOnly) ? (
          <span className="ml-auto flex items-center">
            {readOnly ? null : <ReactionPicker variant="icon" onPick={reactions.toggle} />}
            {comment.canEdit ? (
              <Button
                type="button"
                variant="ghost"
                size="icon-sm"
                className="text-muted-foreground"
                aria-label={m.edit}
                title={m.edit}
                onClick={() => {
                  draft.current = comment.content;
                  setEmpty(isBlankDoc(comment.content));
                  files.reset(comment.attachments);
                  setEditing(true);
                  setRevision((current) => current + 1);
                }}
              >
                <PencilIcon />
              </Button>
            ) : null}
            {comment.canDelete ? (
              <Button
                type="button"
                variant="ghost"
                size={confirming ? "sm" : "icon-sm"}
                disabled={removal.isPending}
                aria-label={confirming ? undefined : m.delete}
                title={confirming ? undefined : m.delete}
                className={confirming ? "text-destructive hover:text-destructive" : "text-muted-foreground"}
                onBlur={() => setConfirming(false)}
                onClick={() => {
                  if (!confirming) setConfirming(true);
                  else removal.mutate(comment.id);
                }}
              >
                <Trash2Icon data-icon={confirming ? "inline-start" : undefined} />
                {confirming ? m.deleteConfirm : null}
              </Button>
            ) : null}
          </span>
        ) : null}
      </div>
      {textless && !editing ? null : (
        <div
          className={cn("flex flex-col gap-2 rounded-md", editing && transfer.dragging && "ring-3 ring-ring/50")}
          onKeyDownCapture={(event) => {
            if (editing && takeSubmitKey(event)) save();
          }}
          {...(editing ? transfer.props : {})}
        >
          <RichTextEditor
            key={revision}
            ref={editor}
            initialContent={comment.content}
            editable={editing}
            autoFocus={editing}
            ariaLabel={editing ? m.editLabel : m.label}
            className="text-sm"
            mentionables={mentionables}
            keepLocal={() => editing}
            onChange={(doc) => {
              draft.current = doc;
              setEmpty(doc === null || isBlankDoc(doc));
            }}
          />
          {editing && transfer.dragging ? (
            <p className="text-[0.8125rem] leading-5 text-muted-foreground" role="status">
              {m.dropFiles}
            </p>
          ) : null}
        </div>
      )}
      {editing ? <DraftFiles files={files.files} onRemove={files.remove} /> : <CommentFiles attachments={comment.attachments} />}
      {!editing && reactions.reactions.length > 0 ? (
        <div className="flex flex-wrap items-center gap-1">
          <ReactionPills reactions={reactions.reactions} onToggle={reactions.toggle} readOnly={readOnly} small />
        </div>
      ) : null}
      {error ? (
        <p role="alert" className="text-[0.8125rem] leading-5 text-destructive">
          {error}
        </p>
      ) : null}
      {editing ? (
        <div className="flex items-center gap-2">
          <AttachButton onFiles={takeFiles} disabled={saving.isPending} />
          <Button
            type="button"
            variant="ghost"
            size="sm"
            disabled={saving.isPending}
            className="ml-auto max-md:h-11"
            onClick={() => {
              files.cancel(comment.attachments);
              stopEditing();
            }}
          >
            {m.cancel}
          </Button>
          <Button type="button" size="sm" disabled={!savable} className="max-md:h-11" onClick={save}>
            {saving.isPending ? <Spinner data-icon="inline-start" aria-hidden="true" /> : null}
            {saving.isPending ? m.saving : m.save}
          </Button>
        </div>
      ) : null}
    </li>
  );
}
