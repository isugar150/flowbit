"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { useMutation } from "@tanstack/react-query";
import { Trash2Icon } from "lucide-react";
import { RichTextEditor, type RichDoc } from "@/components/editor/rich-text-editor";
import { SaveStatus } from "@/components/editor/save-status";
import { Button } from "@/components/ui/button";
import { apiErrorMessage } from "@/lib/api/error-message";
import { useAutosave } from "@/lib/use-autosave";
import { useRemoteValue } from "@/lib/use-remote-value";
import { messages } from "@/messages";
import { deleteDocument, updateDocument } from "../api";
import type { IssueDocument } from "../types";

const m = messages.issues.documents;

/**
 * Title and body of a document (of an issue or of a project), edited in place and saved a moment after typing, the
 * same way an issue is. Deleting asks for a second press and goes back to the issue or the project.
 */
export function DocumentEditor({ document }: { document: IssueDocument }) {
  const router = useRouter();
  const [confirming, setConfirming] = useState(false);
  const title = useAutosave(async (value: string) => {
    await updateDocument({ id: document.id, changes: { title: value } });
    // The title is also in the page header, which the server rendered.
    router.refresh();
  });
  const content = useAutosave((doc: RichDoc | null) => updateDocument({ id: document.id, changes: { content: doc } }));
  const status = content.status !== "idle" ? content.status : title.status;
  const titleInput = useRemoteValue<HTMLInputElement>(document.title, title.isDirty);
  const remove = useMutation({
    mutationFn: deleteDocument,
    onSuccess: () => {
      router.replace(document.issue ? `/issue/${document.issue.key}` : `/project/${document.project?.id ?? ""}`);
      router.refresh();
    },
  });

  return (
    <article className="mx-auto flex w-full max-w-3xl flex-col gap-4 px-6 pt-6 pb-16">
      <div className="flex items-center justify-end gap-2">
        <SaveStatus status={status} />
        {document.canEdit ? (
          <Button
            type="button"
            variant="ghost"
            size="sm"
            className="text-muted-foreground hover:text-destructive max-md:h-11"
            disabled={remove.isPending}
            onBlur={() => setConfirming(false)}
            onClick={() => (confirming ? remove.mutate(document.id) : setConfirming(true))}
          >
            <Trash2Icon data-icon="inline-start" />
            {confirming ? m.deleteConfirm : m.delete}
          </Button>
        ) : null}
      </div>
      {remove.isError ? (
        <p role="alert" className="text-[0.8125rem] leading-5 text-destructive">
          {apiErrorMessage(remove.error)}
        </p>
      ) : null}
      {document.canEdit ? (
        <div data-presence-id="title">
          <input
            ref={titleInput}
            defaultValue={document.title}
            placeholder={m.untitled}
            aria-label={m.titleLabel}
            maxLength={200}
            autoComplete="off"
            onChange={(event) => title.schedule(event.target.value)}
            onBlur={() => void title.flush()}
            className="block w-full bg-transparent text-[1.375rem] leading-[1.875rem] font-semibold outline-none placeholder:text-muted-foreground"
          />
        </div>
      ) : (
        <h2 className="text-[1.375rem] leading-[1.875rem] font-semibold">{document.title || m.untitled}</h2>
      )}
      <div data-presence-id="body">
        <RichTextEditor
          initialContent={document.content}
          editable={document.canEdit}
          placeholder={m.bodyPlaceholder}
          ariaLabel={m.bodyLabel}
          className="min-h-64"
          onChange={content.schedule}
          keepLocal={content.isDirty}
        />
      </div>
    </article>
  );
}
