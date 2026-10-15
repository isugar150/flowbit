"use client";

import { useRouter } from "next/navigation";
import { RichTextEditor, type RichDoc } from "@/components/editor/rich-text-editor";
import { SaveStatus } from "@/components/editor/save-status";
import { createDocument } from "@/features/issues/api";
import type { IssueDocumentSummary } from "@/features/issues/types";
import { useAutosave } from "@/lib/use-autosave";
import { messages } from "@/messages";
import { updateProject } from "../api";
import type { Project } from "../types";
import { DeletedDocuments } from "@/features/issues/components/deleted-documents";

const m = messages.projects;

/**
 * The description of a project, written in place on the overview.
 * There is no edit mode and no save button: people who may edit just type, and it is saved.
 */
export function ProjectOverviewEditor({
  project,
  documents,
  canEdit,
}: {
  project: Project;
  /** The documents made on this overview; they sit in the description as page blocks. */
  documents: IssueDocumentSummary[];
  canEdit: boolean;
}) {
  const router = useRouter();
  const content = useAutosave((doc: RichDoc | null) => updateProject({ id: project.id, changes: { content: doc } }));
  const status = content.status;

  return (
    <div className="flex flex-col gap-6">
      <section data-presence-id="description" className="flex flex-col gap-2">
        <div className="flex items-center justify-between gap-3">
          <h3 className="text-[0.8125rem] leading-5 font-medium text-muted-foreground">{m.descriptionTitle}</h3>
          <SaveStatus status={status} />
        </div>
        {canEdit || project.content ? (
          <RichTextEditor
            initialContent={project.content}
            editable={canEdit}
            placeholder={m.contentPlaceholder}
            ariaLabel={m.contentLabel}
            className="min-h-40"
            onChange={content.schedule}
            keepLocal={content.isDirty}
            // "/문서" in the description makes a document on this project and puts it there as a block.
            documents={documents}
            onCreateDocument={
              canEdit
                ? async (title) => {
                    const document = await createDocument({ owner: { projectId: project.id }, title });
                    router.refresh();
                    return { id: document.id, title: document.title };
                  }
                : undefined
            }
          />
        ) : (
          <p className="text-muted-foreground">{m.noDescription}</p>
        )}
        <DeletedDocuments path={`/api/v1/projects/${encodeURIComponent(project.id)}/documents?deleted=true`} canEdit={canEdit} />
      </section>
    </div>
  );
}
