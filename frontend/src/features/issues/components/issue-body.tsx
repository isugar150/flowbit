"use client";

import { useRouter } from "next/navigation";
import { RichTextEditor, type RichDoc } from "@/components/editor/rich-text-editor";
import { SaveStatus } from "@/components/editor/save-status";
import { useAutosave } from "@/lib/use-autosave";
import { useRemoteValue } from "@/lib/use-remote-value";
import { messages } from "@/messages";
import { createDocument, updateIssue } from "../api";
import type { Issue } from "../types";

const m = messages.issues;

/**
 * Title and body of one issue, edited in place by people who may edit. There is no edit mode
 * and no save button; what is typed is saved a moment later.
 */
export function IssueBody({ issue, canEdit }: { issue: Issue; canEdit: boolean }) {
  const router = useRouter();
  const title = useAutosave(async (value: string) => {
    // An emptied title is not saved; the issue keeps its last title.
    if (!value.trim()) return;
    await updateIssue({ number: issue.number, changes: { title: value } });
    // The title also appears in lists that the server rendered.
    router.refresh();
  });
  const content = useAutosave((doc: RichDoc | null) =>
    updateIssue({ number: issue.number, changes: { content: doc } }),
  );
  const status = content.status !== "idle" ? content.status : title.status;
  const titleInput = useRemoteValue<HTMLInputElement>(issue.title, title.isDirty);

  return (
    <>
      <div className="flex flex-col gap-1">
        <div className="flex items-center justify-between gap-3">
          <span className="text-[0.8125rem] leading-5 text-muted-foreground tabular-nums">{issue.key}</span>
          <SaveStatus status={status} />
        </div>
        {canEdit ? (
          <div data-presence-id="title">
          <input
            ref={titleInput}
            defaultValue={issue.title}
            aria-label={m.titleLabel}
            maxLength={200}
            autoComplete="off"
            onChange={(event) => title.schedule(event.target.value)}
            onBlur={() => void title.flush()}
            onKeyDown={(event) => {
              if (event.key === "Enter" && !event.nativeEvent.isComposing) event.currentTarget.blur();
            }}
            className="block w-full bg-transparent text-[1.375rem] leading-[1.875rem] font-semibold outline-none"
          />
          </div>
        ) : (
          <h2 className="text-[1.375rem] leading-[1.875rem] font-semibold">{issue.title}</h2>
        )}
      </div>
      {canEdit || issue.content ? (
        <div data-presence-id="body">
        <RichTextEditor
          initialContent={issue.content}
          editable={canEdit}
          placeholder={m.bodyPlaceholder}
          ariaLabel={m.descriptionLabel}
          className="min-h-40"
          onChange={content.schedule}
          keepLocal={content.isDirty}
          // "/문서" in the body makes a document on this issue and leaves a link to it.
          onCreateDocument={
            canEdit
              ? async () => {
                  const document = await createDocument({ number: issue.number, title: messages.issues.documents.untitled });
                  router.refresh();
                  return { href: `/doc/${document.id}`, title: document.title };
                }
              : undefined
          }
        />
        </div>
      ) : (
        <p className="text-muted-foreground">{m.noDescription}</p>
      )}
    </>
  );
}
