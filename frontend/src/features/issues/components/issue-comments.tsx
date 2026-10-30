"use client";

import { useId, useRef, useState } from "react";
import { CheckIcon, ChevronDownIcon, ChevronRightIcon, MessageSquareReplyIcon, PencilIcon, RotateCcwIcon, Trash2Icon } from "lucide-react";
import { TimeAgo } from "@/components/dates";
import type { MentionPerson } from "@/components/editor/mention";
import { RichTextEditor, type RichDoc, type RichTextEditorHandle } from "@/components/editor/rich-text-editor";
import { ConfirmDiscardDialog } from "@/components/confirm-discard-dialog";
import { ModKey } from "@/components/mod-key";
import { PersonAvatar, personName } from "@/components/person";
import { Button } from "@/components/ui/button";
import { Spinner } from "@/components/ui/spinner";
import { apiErrorMessage } from "@/lib/api/error-message";
import { useOptimisticMutation, useRefreshingMutation } from "@/lib/use-refreshing-mutation";
import { cn } from "@/lib/utils";
import { messages } from "@/messages";
import type { CommentApi } from "../comment-api";
import type { IssueComment } from "../types";
import { AttachButton, CommentFiles, DraftFiles, useCommentFiles, useFileTransfer } from "./comment-files";
import { ReactionPicker, ReactionPills, useReactions } from "./issue-reactions";

const m = messages.issues.comments;

/** Plain text for the collapsed comment; never renders uploaded HTML. */
function commentPreview(node: unknown): string {
  if (!node || typeof node !== "object") return "";
  const { text, content, type, attrs } = node as { text?: string; content?: unknown[]; type?: string; attrs?: { label?: string; name?: string } };
  if (text) return text;
  if (type === "mention") return attrs?.label ?? attrs?.name ?? "";
  return (content ?? []).map(commentPreview).join(type === "doc" ? " " : "");
}

/** Ctrl/⌘+Enter sends; caught before the editor, which would break the line instead. */
export function takeSubmitKey(event: React.KeyboardEvent): boolean {
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
 * Where a comment is written, at the top of the activity of an issue (D-167). The same editor as the
 * body, so `/` blocks and pasted pictures work; `@` tags a person, who is then told through every
 * notification channel they have set up and follows the issue from then on. Pictures, and other
 * files (up to ten) from the paperclip, a drop or a paste, are sent along: pictures inside the
 * text, the rest as files under it.
 *
 * With `parentId` it writes a reply to that comment: the same, a little smaller, with a way to give
 * up (`onCancel`); `onSent` is called when the reply has gone.
 */
export function CommentComposer({
  api,
  mentionables,
  parentId,
  onSent,
  onCancel,
}: {
  api: CommentApi;
  mentionables: MentionPerson[];
  parentId?: string;
  onSent?: () => void;
  onCancel?: () => void;
}) {
  const reply = parentId !== undefined;
  const draft = useRef<RichDoc | null>(null);
  const editor = useRef<RichTextEditorHandle>(null);
  const [empty, setEmpty] = useState(true);
  // A new key empties the editor after the comment is sent.
  const [revision, setRevision] = useState(0);
  const mutation = useRefreshingMutation(api.create, () => {
    draft.current = null;
    setEmpty(true);
    setRevision((current) => current + 1);
    onSent?.();
  });
  // Files are blocks of the text now (D-182), so a comment is its text alone.
  const sendable = !empty && !mutation.isPending;
  const submit = () => {
    if (sendable) mutation.mutate({ content: draft.current, attachmentIds: [], parentId });
  };
  // A reply with something written asks before it is given up (D-182).
  const [asking, setAsking] = useState(false);
  const giveUp = () => {
    setAsking(false);
    onCancel?.();
  };
  const tryGiveUp = () => {
    if (!empty) setAsking(true);
    else giveUp();
  };
  const takeFiles = (list: File[]) => void editor.current?.insertFiles(list);
  const transfer = useFileTransfer(takeFiles);

  return (
    <div
      data-presence-id={reply ? undefined : "comment"}
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
        placeholder={reply ? m.replyPlaceholder : m.placeholder}
        ariaLabel={reply ? m.replyLabel : m.label}
        autoFocus={reply}
        className={cn("text-sm", reply ? "min-h-10" : "min-h-14")}
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
      {mutation.isError ? (
        <p role="alert" className="text-[0.8125rem] leading-5 text-destructive">
          {apiErrorMessage(mutation.error)}
        </p>
      ) : null}
      <div className="flex items-center gap-2">
        <AttachButton onFiles={takeFiles} disabled={mutation.isPending} />
        <span className="ml-auto text-[0.75rem] leading-4 text-muted-foreground max-md:hidden" aria-hidden="true">
          <ModKey />
          +Enter
        </span>
        {reply ? (
          <Button type="button" variant="ghost" size="sm" disabled={mutation.isPending} className="max-md:h-11" onClick={tryGiveUp}>
            {m.cancel}
          </Button>
        ) : null}
        <Button type="button" size="sm" disabled={!sendable} className="max-md:h-11" onClick={submit}>
          {mutation.isPending ? <Spinner data-icon="inline-start" aria-hidden="true" /> : null}
          {mutation.isPending ? m.submitting : reply ? m.replySubmit : m.submit}
        </Button>
      </div>
      {reply ? <ConfirmDiscardDialog open={asking} onKeep={() => setAsking(false)} onDiscard={giveUp} /> : null}
    </div>
  );
}

/**
 * One comment in the activity of an issue, with the replies under it (D-167). Its author can change
 * it in place; they or an admin can delete it. Anyone who sees it can put an emoji on it
 * (`readOnly`, a deleted issue, only shows them). Where people may write, a "답글" button opens a
 * box for a reply; a reply itself cannot be answered (`isReply`). A comment that was deleted while
 * it has replies is only a line saying so, with the replies under it.
 */
export function CommentItem({
  api,
  comment,
  replies = [],
  mentionables,
  readOnly = false,
  canReply = false,
  isReply = false,
}: {
  /** What the comment does to the server: its issue's calls, or those of the page it is on. */
  api: CommentApi;
  comment: IssueComment;
  /** The answers to it, oldest first. */
  replies?: IssueComment[];
  mentionables: MentionPerson[];
  readOnly?: boolean;
  /** Whether the person may write here at all (the same as for a new comment). */
  canReply?: boolean;
  isReply?: boolean;
}) {
  const panelId = useId();
  const [expandedFor, setExpandedFor] = useState<string | null>(null);
  const resolution = useOptimisticMutation(comment.resolved ?? false, (_current: boolean, resolved: boolean) => resolved,
    (resolved: boolean) => api.setResolved({ id: comment.id, resolved }));
  const resolved = !isReply && resolution.value;
  const resolutionKey = comment.resolvedAt ?? "pending";
  const expanded = !resolved || expandedFor === resolutionKey;
  const [replying, setReplying] = useState(false);
  const [editing, setEditing] = useState(false);
  const draft = useRef<RichDoc | null>(comment.content);
  const editor = useRef<RichTextEditorHandle>(null);
  // A new key puts the stored comment back when editing starts or is given up.
  const [revision, setRevision] = useState(0);
  const [empty, setEmpty] = useState(false);
  // Whether the comment being changed differs from the saved one; giving up then asks first (D-182).
  const [changed, setChanged] = useState(false);
  const [asking, setAsking] = useState(false);
  // The first press asks, the second one deletes (the same as deleting an issue).
  const [confirming, setConfirming] = useState(false);
  const files = useCommentFiles(api, comment.attachments);
  const reactions = useReactions(comment.reactions, (emoji) => api.react({ id: comment.id, emoji }));
  const stopEditing = () => {
    setEditing(false);
    setChanged(false);
    setAsking(false);
    setRevision((current) => current + 1);
  };
  const giveUpEditing = () => {
    files.cancel(comment.attachments);
    stopEditing();
  };
  const saving = useRefreshingMutation(api.update, stopEditing);
  const removal = useRefreshingMutation(api.remove);
  const savable = (!empty || files.ids.length > 0) && !files.busy && !saving.isPending;
  const save = () => {
    if (savable) saving.mutate({ id: comment.id, content: empty ? null : draft.current, attachmentIds: files.ids });
  };
  const takeFiles = (list: File[]) => void editor.current?.insertFiles(list);
  const transfer = useFileTransfer(takeFiles);
  const failure = saving.error ?? removal.error ?? resolution.error;
  const error = files.error ?? (failure ? apiErrorMessage(failure) : null);
  // A comment of files alone has no text to show.
  const textless = isBlankDoc(comment.content) && comment.attachments.length > 0;

  const answers =
    replies.length > 0 || (canReply && !readOnly && !comment.deleted && !isReply) ? (
      <div className={cn("flex flex-col gap-2", comment.deleted ? "pl-3" : "")}>
        {replies.length > 0 ? (
          <ol aria-label={m.replies(replies.length)} className="flex flex-col gap-2 border-l-2 pl-3">
            {replies.map((reply) => (
              <CommentItem key={reply.id} api={api} comment={reply} mentionables={mentionables} readOnly={readOnly} isReply />
            ))}
          </ol>
        ) : null}
        {canReply && !readOnly && !comment.deleted && !isReply ? (
          replying ? (
            <CommentComposer api={api} mentionables={mentionables} parentId={comment.id} onSent={() => setReplying(false)} onCancel={() => setReplying(false)} />
          ) : (
            <Button type="button" variant="ghost" size="sm" className="w-fit text-muted-foreground max-md:h-11" onClick={() => setReplying(true)}>
              <MessageSquareReplyIcon data-icon="inline-start" />
              {m.reply}
            </Button>
          )
        ) : null}
      </div>
    ) : null;

  // Deleted, and only here because of its replies.
  if (comment.deleted) {
    return (
      <li className="flex flex-col gap-2 rounded-lg border border-dashed p-3">
        <p className="text-[0.8125rem] leading-5 text-muted-foreground">{m.deletedComment}</p>
        {answers}
      </li>
    );
  }

  return (
    <li className={cn("flex flex-col gap-2", isReply ? "py-1" : "rounded-lg border p-3", resolved && "bg-muted text-muted-foreground")} data-resolved={resolved || undefined}>
      <div className="flex min-h-7 flex-wrap items-center gap-x-2 gap-y-1 text-[0.8125rem] leading-5">
        {resolved ? (
          <Button type="button" variant="ghost" size="icon-sm" className="text-muted-foreground" aria-controls={panelId} aria-expanded={expanded}
            aria-label={expanded ? m.collapseResolved : m.expandResolved} title={expanded ? m.collapseResolved : m.expandResolved}
            onClick={() => setExpandedFor(expanded ? null : resolutionKey)}>
            {expanded ? <ChevronDownIcon /> : <ChevronRightIcon />}
          </Button>
        ) : null}
        <PersonAvatar person={comment.author} />
        <span className="min-w-0 truncate font-medium">{personName(comment.author)}</span>
        <span className="text-muted-foreground">
          <TimeAgo iso={comment.createdAt} />
          {comment.updatedAt !== comment.createdAt ? ` · ${m.edited}` : null}
        </span>
        {resolved ? <span className="inline-flex items-center gap-1 text-muted-foreground"><CheckIcon className="size-3.5" />{m.resolved}</span> : null}
        {!editing && (comment.canEdit || comment.canDelete || !readOnly) ? (
          <span className="ml-auto flex items-center">
            {!isReply && canReply && !readOnly && !replying ? (
              <Button type="button" variant="ghost" size="sm" className="text-muted-foreground"
                onClick={() => { setExpandedFor(null); resolution.mutate(!resolved); }}>
                {resolved ? <RotateCcwIcon data-icon="inline-start" /> : <CheckIcon data-icon="inline-start" />}
                {resolved ? m.reopen : m.resolve}
              </Button>
            ) : null}
            {readOnly || !expanded ? null : <ReactionPicker variant="icon" onPick={reactions.toggle} />}
            {comment.canEdit && expanded ? (
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
                  setChanged(false);
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
      {resolved && comment.resolvedBy && comment.resolvedAt ? (
        <p className="text-xs text-muted-foreground">{m.resolvedBy(personName(comment.resolvedBy))} · <TimeAgo iso={comment.resolvedAt} /></p>
      ) : null}
      {resolved && !expanded ? (
        <button type="button" className="min-w-0 truncate text-left text-sm text-muted-foreground line-through focus-visible:outline-ring"
          aria-expanded={false} aria-controls={panelId} aria-label={m.expandResolved} onClick={() => setExpandedFor(resolutionKey)}>
          {commentPreview(comment.content) || comment.attachments.map((file) => file.fileName).join(", ") || m.attachmentComment}
          {replies.length ? <span className="ml-2 inline-block text-xs no-underline">{m.replies(replies.length)}</span> : null}
        </button>
      ) : null}
      <div id={panelId} hidden={!expanded} className={expanded ? "flex flex-col gap-2" : "hidden"}>
        {textless && !editing ? null : (
          <div
            className={cn("flex flex-col gap-2 rounded-md", resolved && !editing && "text-muted-foreground line-through [&_.tiptap]:text-muted-foreground [&_.tiptap_*]:text-muted-foreground", editing && transfer.dragging && "ring-3 ring-ring/50")}
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
                setChanged(true);
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
        {!editing ? answers : null}
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
                if (changed || files.ids.join() !== comment.attachments.map((file) => file.id).join()) setAsking(true);
                else giveUpEditing();
              }}
            >
              {m.cancel}
            </Button>
            <Button type="button" size="sm" disabled={!savable} className="max-md:h-11" onClick={save}>
              {saving.isPending ? <Spinner data-icon="inline-start" aria-hidden="true" /> : null}
              {saving.isPending ? m.saving : m.save}
            </Button>
            <ConfirmDiscardDialog open={asking} onKeep={() => setAsking(false)} onDiscard={giveUpEditing} />
          </div>
        ) : null}
      </div>
      {error ? <p role="alert" className="text-[0.8125rem] leading-5 text-destructive">{error}</p> : null}
    </li>
  );
}
