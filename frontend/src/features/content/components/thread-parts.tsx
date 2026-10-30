"use client";

import { useRef, useState } from "react";
import { useMutation } from "@tanstack/react-query";
import { CheckIcon, PencilIcon, RotateCcwIcon, Trash2Icon } from "lucide-react";
import { TimeAgo } from "@/components/dates";
import type { MentionPerson } from "@/components/editor/mention";
import { RichTextEditor, type RichDoc } from "@/components/editor/rich-text-editor";
import { ModKey } from "@/components/mod-key";
import { PersonAvatar, personName } from "@/components/person";
import { Button } from "@/components/ui/button";
import { Spinner } from "@/components/ui/spinner";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { takeSubmitKey } from "@/features/issues/components/issue-comments";
import { apiErrorMessage } from "@/lib/api/error-message";
import { cn } from "@/lib/utils";
import { messages } from "@/messages";
import { deleteThreadComment, editThreadComment, reopenThread, replyToThread, resolveThread } from "../api";
import type { ContentTarget, ContentThread, ThreadComment } from "../types";
import { useFeedRefresh } from "../use-content-feed";

const m = messages.content.block;

/** A small icon button that names itself in a hint of the app's own, not the browser's. */
export function HintButton({
  label,
  children,
  className,
  ...props
}: Omit<React.ComponentProps<typeof Button>, "aria-label"> & { label: string }) {
  return (
    <Tooltip>
      <TooltipTrigger
        delay={0}
        render={<Button type="button" variant="ghost" size="icon-sm" aria-label={label} className={cn("text-muted-foreground", className)} {...props} />}
      >
        {children}
      </TooltipTrigger>
      <TooltipContent side="top">{label}</TooltipContent>
    </Tooltip>
  );
}

/**
 * Where a comment on a block is written: text and `@` tags only. Ctrl/⌘+Enter sends. `onSubmit`
 * does the sending; the box empties once it has succeeded and shows why when it has not.
 */
export function ThreadComposer({
  label,
  placeholder,
  submitLabel,
  mentionables,
  onSubmit,
  autoFocus = false,
}: {
  label: string;
  placeholder: string;
  submitLabel: string;
  mentionables: MentionPerson[];
  onSubmit: (content: RichDoc) => Promise<unknown>;
  autoFocus?: boolean;
}) {
  const draft = useRef<RichDoc | null>(null);
  const [empty, setEmpty] = useState(true);
  // A new key empties the editor after the comment is sent.
  const [revision, setRevision] = useState(0);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const send = async () => {
    if (!draft.current || pending) return;
    setPending(true);
    setError(null);
    try {
      await onSubmit(draft.current);
      draft.current = null;
      setEmpty(true);
      setRevision((current) => current + 1);
    } catch (failure) {
      setError(apiErrorMessage(failure));
    } finally {
      setPending(false);
    }
  };

  return (
    <div
      data-presence-id=""
      className="flex flex-col gap-1.5 rounded-lg border p-2 focus-within:border-ring focus-within:ring-3 focus-within:ring-ring/50"
      onKeyDownCapture={(event) => {
        if (takeSubmitKey(event)) void send();
      }}
    >
      <RichTextEditor
        key={revision}
        plain
        initialContent={null}
        placeholder={placeholder}
        ariaLabel={label}
        className="min-h-8 text-sm"
        mentionables={mentionables}
        autoFocus={autoFocus}
        onChange={(doc) => {
          draft.current = doc;
          setEmpty(doc === null);
        }}
      />
      {error ? (
        <p role="alert" className="text-[0.8125rem] leading-5 text-destructive">
          {error}
        </p>
      ) : null}
      <div className="flex items-center justify-end gap-2">
        <span className="text-[0.75rem] leading-4 text-muted-foreground max-md:hidden" aria-hidden="true">
          <ModKey />
          +Enter
        </span>
        <Button type="button" size="sm" disabled={empty || pending} className="max-md:h-11" onClick={() => void send()}>
          {pending ? <Spinner data-icon="inline-start" aria-hidden="true" /> : null}
          {pending ? m.sending : submitLabel}
        </Button>
      </div>
    </div>
  );
}

/** One comment of a thread. Its author changes it in place; they or a workspace admin delete it (the first one takes the thread with it). */
function ThreadCommentItem({
  target,
  comment,
  first,
  mentionables,
}: {
  target: ContentTarget;
  comment: ThreadComment;
  /** The comment that opened the thread. */
  first: boolean;
  mentionables: MentionPerson[];
}) {
  const refresh = useFeedRefresh(target);
  const [editing, setEditing] = useState(false);
  const draft = useRef<RichDoc | null>(comment.content);
  // A new key puts the stored comment back when editing starts or is given up.
  const [revision, setRevision] = useState(0);
  const [empty, setEmpty] = useState(false);
  // The first press asks, the second one deletes.
  const [confirming, setConfirming] = useState(false);
  const stopEditing = () => {
    setEditing(false);
    setRevision((current) => current + 1);
  };
  const save = useMutation({
    mutationFn: editThreadComment,
    onSuccess: () => {
      stopEditing();
      refresh();
    },
  });
  const remove = useMutation({ mutationFn: deleteThreadComment, onSuccess: refresh });
  const failure = save.error ?? remove.error;
  const submit = () => {
    if (draft.current && !empty && !save.isPending) save.mutate({ id: comment.id, content: draft.current });
  };

  return (
    <li className="flex flex-col gap-1">
      <div className="flex min-h-6 items-center gap-2 text-[0.8125rem] leading-5">
        <PersonAvatar person={comment.author} />
        <span className="min-w-0 truncate font-medium">{personName(comment.author)}</span>
        <span className="shrink-0 text-muted-foreground">
          <TimeAgo iso={comment.createdAt} />
          {comment.updatedAt !== comment.createdAt ? ` · ${m.edited}` : null}
        </span>
        {!editing && (comment.canEdit || comment.canDelete) ? (
          <span className="ml-auto flex shrink-0 items-center">
            {comment.canEdit ? (
              <HintButton
                label={m.edit}
                className="size-6"
                onClick={() => {
                  draft.current = comment.content;
                  setEmpty(false);
                  setEditing(true);
                  setRevision((current) => current + 1);
                }}
              >
                <PencilIcon />
              </HintButton>
            ) : null}
            {comment.canDelete ? (
              <Button
                type="button"
                variant="ghost"
                size={confirming ? "xs" : "icon-sm"}
                disabled={remove.isPending}
                aria-label={confirming ? undefined : m.delete}
                className={cn(confirming ? "text-destructive hover:text-destructive" : "size-6 text-muted-foreground")}
                onBlur={() => setConfirming(false)}
                onClick={() => {
                  if (!confirming) setConfirming(true);
                  else remove.mutate(comment.id);
                }}
              >
                <Trash2Icon data-icon={confirming ? "inline-start" : undefined} />
                {confirming ? (first ? m.deleteThreadConfirm : m.deleteConfirm) : null}
              </Button>
            ) : null}
          </span>
        ) : null}
      </div>
      <div
        data-presence-id={editing ? "" : undefined}
        className="pl-7"
        onKeyDownCapture={(event) => {
          if (editing && takeSubmitKey(event)) submit();
        }}
      >
        <RichTextEditor
          key={revision}
          plain
          initialContent={comment.content}
          editable={editing}
          autoFocus={editing}
          ariaLabel={editing ? m.editLabel : m.panel}
          className="text-sm"
          mentionables={mentionables}
          keepLocal={() => editing}
          onChange={(doc) => {
            draft.current = doc;
            setEmpty(doc === null);
          }}
        />
      </div>
      {failure ? (
        <p role="alert" className="pl-7 text-[0.8125rem] leading-5 text-destructive">
          {apiErrorMessage(failure)}
        </p>
      ) : null}
      {editing ? (
        <div className="flex items-center justify-end gap-2 pl-7">
          <Button type="button" variant="ghost" size="sm" disabled={save.isPending} className="max-md:h-11" onClick={stopEditing}>
            {m.cancel}
          </Button>
          <Button type="button" size="sm" disabled={empty || save.isPending} className="max-md:h-11" onClick={submit}>
            {save.isPending ? <Spinner data-icon="inline-start" aria-hidden="true" /> : null}
            {save.isPending ? m.saving : m.save}
          </Button>
        </div>
      ) : null}
    </li>
  );
}

/**
 * A thread on a block: the comments, a box to answer in, and "해결" (or "다시 열기" when it is
 * resolved). With `quote` the block's text is shown over it, and a press on that goes to the block
 * (in the activity, where the thread is away from its block). `collapseResolved` folds a resolved
 * thread to one line.
 */
export function ThreadCard({
  target,
  thread,
  canComment,
  mentionables,
  quote = false,
  collapseResolved = false,
  onGoToBlock,
}: {
  target: ContentTarget;
  thread: ContentThread;
  /** Whether the person may still answer, resolve and reopen. */
  canComment: boolean;
  mentionables: MentionPerson[];
  quote?: boolean;
  collapseResolved?: boolean;
  onGoToBlock?: (blockId: string) => void;
}) {
  const refresh = useFeedRefresh(target);
  const reply = useMutation({ mutationFn: (content: RichDoc) => replyToThread({ threadId: thread.id, content }), onSuccess: refresh });
  const toggle = useMutation({
    mutationFn: () => (thread.resolved ? reopenThread(thread.id) : resolveThread(thread.id)),
    onSuccess: refresh,
  });

  const body = (
    <div className="flex flex-col gap-2">
      {quote ? (
        <div className="flex flex-col gap-0.5">
          <button
            type="button"
            disabled={thread.orphaned || !onGoToBlock}
            aria-label={`${m.goToBlock}: ${thread.quote}`}
            onClick={() => onGoToBlock?.(thread.blockId)}
            className="line-clamp-2 border-l-2 pl-2 text-left text-[0.8125rem] leading-5 text-muted-foreground outline-none enabled:hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50"
          >
            {thread.quote}
          </button>
          {thread.orphaned ? <span className="pl-2 text-xs leading-4 text-muted-foreground">{m.orphaned}</span> : null}
        </div>
      ) : thread.orphaned ? (
        <span className="text-xs leading-4 text-muted-foreground">{m.orphaned}</span>
      ) : null}
      <ul className="flex flex-col gap-2.5">
        {thread.comments.map((comment, index) => (
          <ThreadCommentItem key={comment.id} target={target} comment={comment} first={index === 0} mentionables={mentionables} />
        ))}
      </ul>
      {thread.resolved && thread.resolvedBy ? (
        <p className="flex items-center gap-1.5 text-xs leading-4 text-muted-foreground">
          <CheckIcon className="size-3.5" aria-hidden="true" />
          <span>
            {m.resolvedBy(personName(thread.resolvedBy))}
            {thread.resolvedAt ? (
              <>
                {" · "}
                <TimeAgo iso={thread.resolvedAt} />
              </>
            ) : null}
          </span>
        </p>
      ) : null}
      {canComment && !thread.resolved ? (
        <ThreadComposer
          label={m.replyLabel}
          placeholder={m.replyPlaceholder}
          submitLabel={m.reply}
          mentionables={mentionables}
          onSubmit={(content) => reply.mutateAsync(content)}
        />
      ) : null}
      {toggle.isError ? (
        <p role="alert" className="text-[0.8125rem] leading-5 text-destructive">
          {apiErrorMessage(toggle.error)}
        </p>
      ) : null}
      {canComment ? (
        <div className="flex justify-end">
          <Button type="button" variant="ghost" size="sm" disabled={toggle.isPending} className="text-muted-foreground max-md:h-11" onClick={() => toggle.mutate()}>
            {toggle.isPending ? (
              <Spinner data-icon="inline-start" aria-hidden="true" />
            ) : thread.resolved ? (
              <RotateCcwIcon data-icon="inline-start" />
            ) : (
              <CheckIcon data-icon="inline-start" />
            )}
            {thread.resolved ? m.reopen : m.resolve}
          </Button>
        </div>
      ) : null}
    </div>
  );

  if (collapseResolved && thread.resolved) {
    return (
      <details className="group rounded-lg border bg-muted/30 p-3">
        <summary className="flex cursor-pointer list-none items-center gap-2 text-[0.8125rem] leading-5 text-muted-foreground outline-none select-none focus-visible:ring-3 focus-visible:ring-ring/50 [&::-webkit-details-marker]:hidden">
          <span aria-hidden="true" className="inline-block transition-transform group-open:rotate-90">
            ›
          </span>
          <CheckIcon className="size-3.5 shrink-0" aria-hidden="true" />
          <span className="min-w-0 flex-1 truncate">{thread.quote || m.resolvedThread}</span>
        </summary>
        <div className="mt-2">{body}</div>
      </details>
    );
  }
  return <div className={cn("rounded-lg border p-3", thread.resolved && "bg-muted/30")}>{body}</div>;
}
