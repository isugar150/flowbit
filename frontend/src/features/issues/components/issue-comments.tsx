"use client";

import { useRef, useState } from "react";
import { PencilIcon, Trash2Icon } from "lucide-react";
import { TimeAgo } from "@/components/dates";
import type { MentionPerson } from "@/components/editor/mention";
import { RichTextEditor, type RichDoc } from "@/components/editor/rich-text-editor";
import { ModKey } from "@/components/mod-key";
import { PersonAvatar, personName } from "@/components/person";
import { Button } from "@/components/ui/button";
import { Spinner } from "@/components/ui/spinner";
import { apiErrorMessage } from "@/lib/api/error-message";
import { useRefreshingMutation } from "@/lib/use-refreshing-mutation";
import { messages } from "@/messages";
import { createComment, deleteComment, updateComment } from "../api";
import type { IssueComment } from "../types";

const m = messages.issues.comments;

/** Ctrl/⌘+Enter sends; caught before the editor, which would break the line instead. */
function takeSubmitKey(event: React.KeyboardEvent): boolean {
  if (event.key !== "Enter" || !(event.ctrlKey || event.metaKey) || event.nativeEvent.isComposing) return false;
  event.preventDefault();
  event.stopPropagation();
  return true;
}

/**
 * Where a comment is written, under the activity of an issue. The same editor as the body, so `/`
 * blocks and pasted pictures work; `@` tags a person, who is then told through every notification
 * channel they have set up and follows the issue from then on.
 */
export function CommentComposer({ number, mentionables }: { number: number; mentionables: MentionPerson[] }) {
  const draft = useRef<RichDoc | null>(null);
  const [empty, setEmpty] = useState(true);
  // A new key empties the editor after the comment is sent.
  const [revision, setRevision] = useState(0);
  const mutation = useRefreshingMutation(createComment, () => {
    draft.current = null;
    setEmpty(true);
    setRevision((current) => current + 1);
  });
  const submit = () => {
    if (draft.current && !mutation.isPending) mutation.mutate({ number, content: draft.current });
  };

  return (
    <div
      data-presence-id="comment"
      className="flex flex-col gap-2 rounded-lg border p-3 focus-within:border-ring focus-within:ring-3 focus-within:ring-ring/50"
      onKeyDownCapture={(event) => {
        if (takeSubmitKey(event)) submit();
      }}
    >
      <RichTextEditor
        key={revision}
        initialContent={null}
        placeholder={m.placeholder}
        ariaLabel={m.label}
        className="min-h-14 text-sm"
        mentionables={mentionables}
        onChange={(doc) => {
          draft.current = doc;
          setEmpty(doc === null);
        }}
      />
      {mutation.isError ? (
        <p role="alert" className="text-[0.8125rem] leading-5 text-destructive">
          {apiErrorMessage(mutation.error)}
        </p>
      ) : null}
      <div className="flex items-center justify-end gap-2">
        <span className="text-[0.75rem] leading-4 text-muted-foreground max-md:hidden" aria-hidden="true">
          <ModKey />
          +Enter
        </span>
        <Button type="button" size="sm" disabled={empty || mutation.isPending} className="max-md:h-11" onClick={submit}>
          {mutation.isPending ? <Spinner data-icon="inline-start" aria-hidden="true" /> : null}
          {mutation.isPending ? m.submitting : m.submit}
        </Button>
      </div>
    </div>
  );
}

/** One comment in the activity of an issue. Its author can change it in place; they or an admin can delete it. */
export function CommentItem({ comment, mentionables }: { comment: IssueComment; mentionables: MentionPerson[] }) {
  const [editing, setEditing] = useState(false);
  const draft = useRef<RichDoc | null>(comment.content);
  // A new key puts the stored comment back when editing starts or is given up.
  const [revision, setRevision] = useState(0);
  const [empty, setEmpty] = useState(false);
  // The first press asks, the second one deletes (the same as deleting an issue).
  const [confirming, setConfirming] = useState(false);
  const stopEditing = () => {
    setEditing(false);
    setRevision((current) => current + 1);
  };
  const saving = useRefreshingMutation(updateComment, stopEditing);
  const removal = useRefreshingMutation(deleteComment);
  const save = () => {
    if (draft.current && !saving.isPending) saving.mutate({ id: comment.id, content: draft.current });
  };
  const error = saving.error ?? removal.error;

  return (
    <li className="flex flex-col gap-2 rounded-lg border p-3">
      <div className="flex min-h-7 flex-wrap items-center gap-x-2 gap-y-1 text-[0.8125rem] leading-5">
        <PersonAvatar person={comment.author} />
        <span className="min-w-0 truncate font-medium">{personName(comment.author)}</span>
        <span className="text-muted-foreground">
          <TimeAgo iso={comment.createdAt} />
          {comment.updatedAt !== comment.createdAt ? ` · ${m.edited}` : null}
        </span>
        {!editing && (comment.canEdit || comment.canDelete) ? (
          <span className="ml-auto flex items-center">
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
                  setEmpty(false);
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
      <div
        onKeyDownCapture={(event) => {
          if (editing && takeSubmitKey(event)) save();
        }}
      >
        <RichTextEditor
          key={revision}
          initialContent={comment.content}
          editable={editing}
          autoFocus={editing}
          ariaLabel={editing ? m.editLabel : m.label}
          className="text-sm"
          mentionables={mentionables}
          keepLocal={() => editing}
          onChange={(doc) => {
            draft.current = doc;
            setEmpty(doc === null);
          }}
        />
      </div>
      {error ? (
        <p role="alert" className="text-[0.8125rem] leading-5 text-destructive">
          {apiErrorMessage(error)}
        </p>
      ) : null}
      {editing ? (
        <div className="flex items-center justify-end gap-2">
          <Button type="button" variant="ghost" size="sm" disabled={saving.isPending} className="max-md:h-11" onClick={stopEditing}>
            {m.cancel}
          </Button>
          <Button type="button" size="sm" disabled={empty || saving.isPending} className="max-md:h-11" onClick={save}>
            {saving.isPending ? <Spinner data-icon="inline-start" aria-hidden="true" /> : null}
            {saving.isPending ? m.saving : m.save}
          </Button>
        </div>
      ) : null}
    </li>
  );
}
