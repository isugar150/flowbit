"use client";

import { useRouter } from "next/navigation";
import { RichTextEditor, type RichDoc } from "@/components/editor/rich-text-editor";
import { SaveStatus } from "@/components/editor/save-status";
import { useAutosave } from "@/lib/use-autosave";
import { messages } from "@/messages";
import { updateIssue } from "../api";
import type { Issue } from "../types";

const m = messages.issues;

/**
 * Title and body of one issue, edited in place by people who may edit. There is no edit mode
 * and no save button; what is typed is saved a moment later.
 */
export function IssueBody({ projectKey, issue, canEdit }: { projectKey: string; issue: Issue; canEdit: boolean }) {
  const router = useRouter();
  const title = useAutosave(async (value: string) => {
    // An emptied title is not saved; the issue keeps its last title.
    if (!value.trim()) return;
    await updateIssue({ projectKey, number: issue.number, changes: { title: value } });
    // The title also appears in lists that the server rendered.
    router.refresh();
  });
  const content = useAutosave((doc: RichDoc | null) =>
    updateIssue({ projectKey, number: issue.number, changes: { content: doc } }),
  );
  const status = content.status !== "idle" ? content.status : title.status;

  return (
    <>
      <div className="flex flex-col gap-1">
        <div className="flex items-center justify-between gap-3">
          <span className="text-[0.8125rem] leading-5 text-muted-foreground tabular-nums">{issue.key}</span>
          <SaveStatus status={status} />
        </div>
        {canEdit ? (
          <input
            defaultValue={issue.title}
            aria-label={m.titleLabel}
            maxLength={200}
            autoComplete="off"
            onChange={(event) => title.schedule(event.target.value)}
            onBlur={() => void title.flush()}
            onKeyDown={(event) => {
              if (event.key === "Enter" && !event.nativeEvent.isComposing) event.currentTarget.blur();
            }}
            className="w-full bg-transparent text-[1.375rem] leading-[1.875rem] font-semibold outline-none"
          />
        ) : (
          <h2 className="text-[1.375rem] leading-[1.875rem] font-semibold">{issue.title}</h2>
        )}
      </div>
      {canEdit || issue.content ? (
        <RichTextEditor
          initialContent={issue.content}
          editable={canEdit}
          placeholder={m.bodyPlaceholder}
          ariaLabel={m.descriptionLabel}
          className="min-h-40"
          onChange={content.schedule}
        />
      ) : (
        <p className="text-muted-foreground">{m.noDescription}</p>
      )}
    </>
  );
}
