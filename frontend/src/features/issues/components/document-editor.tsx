"use client";

import { useMemo, useState } from "react";
import { useRouter } from "next/navigation";
import { useMutation } from "@tanstack/react-query";
import { Trash2Icon } from "lucide-react";
import type { MentionPerson } from "@/components/editor/mention";
import { RichTextEditor, type RichDoc } from "@/components/editor/rich-text-editor";
import { SaveStatus } from "@/components/editor/save-status";
import { Button } from "@/components/ui/button";
import { BlockComments } from "@/features/content/components/block-comments";
import { ContentActivity } from "@/features/content/components/content-activity";
import { useContentFeed } from "@/features/content/use-content-feed";
import { apiErrorMessage } from "@/lib/api/error-message";
import { useAutosave } from "@/lib/use-autosave";
import { useRemoteValue } from "@/lib/use-remote-value";
import { messages } from "@/messages";
import { createDocument, deleteDocument, restoreDocument, updateDocument } from "../api";
import type { IssueDocument } from "../types";

const m = messages.issues.documents;

/**
 * Title and body of a document (of an issue or of a project), edited in place and saved a moment after typing, the
 * same way an issue is. Documents inside it sit in the body as page blocks. Deleting asks for a
 * second press, takes the documents inside along, and goes back to where the document was.
 */
export function DocumentEditor({ document, mentionables }: { document: IssueDocument; mentionables: MentionPerson[] }) {
  const router = useRouter();
  const target = useMemo(() => ({ type: "document" as const, id: document.id }), [document.id]);
  const feed = useContentFeed(target, document);
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
      const parent = document.ancestors.at(-1);
      router.replace(
        parent ? `/doc/${parent.id}` : document.issue ? `/issue/${document.issue.key}` : `/project/${document.project?.id ?? ""}`,
      );
      router.refresh();
    },
  });

  const restore = useMutation({ mutationFn: restoreDocument, onSuccess: () => router.refresh() });

  return (
    <article className="mx-auto flex w-full max-w-3xl flex-col gap-4 px-6 pt-6 pb-16">
      {document.deletedAt ? (
        <div role="status" className="flex flex-wrap items-center justify-between gap-2 rounded-lg border bg-muted px-3 py-2 text-[0.8125rem] leading-5">
          <span>{m.deletedNotice}</span>
          {document.canRestore ? (
            <Button type="button" variant="outline" size="sm" className="max-md:h-11" disabled={restore.isPending} onClick={() => restore.mutate(document.id)}>
              {m.restore}
            </Button>
          ) : null}
        </div>
      ) : null}
      {restore.isError ? (
        <p role="alert" className="text-[0.8125rem] leading-5 text-destructive">
          {apiErrorMessage(restore.error)}
        </p>
      ) : null}
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
          // Each block can be commented on, by anyone who sees the document (D-165).
          blockIds
          overlay={({ editor, container }) => (
            <BlockComments
              editor={editor}
              container={container}
              target={target}
              threads={feed.data?.threads ?? []}
              canComment={feed.data?.canComment ?? false}
              mentionables={mentionables}
            />
          )}
          documents={document.children}
          // "/문서" makes a document inside this one. Only a project's documents nest, five levels deep.
          onCreateDocument={
            document.canAddChild
              ? async (title) => {
                  const child = await createDocument({ owner: { documentId: document.id }, title });
                  router.refresh();
                  return { id: child.id, title: child.title };
                }
              : undefined
          }
        />
      </div>
      {/* What changed in the document and what was said about it, as on an issue (D-165). */}
      <ContentActivity target={target} watch={document} mentionables={mentionables} />
    </article>
  );
}
