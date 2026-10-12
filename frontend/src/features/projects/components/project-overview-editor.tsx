"use client";

import { useRouter } from "next/navigation";
import { RichTextEditor, type RichDoc } from "@/components/editor/rich-text-editor";
import { SaveStatus } from "@/components/editor/save-status";
import { createDocument } from "@/features/issues/api";
import { useAutosave } from "@/lib/use-autosave";
import { useRemoteValue } from "@/lib/use-remote-value";
import { messages } from "@/messages";
import { updateProject } from "../api";
import type { Project } from "../types";

const m = messages.projects;

/**
 * The summary line and the description of a project, written in place on the overview.
 * There is no edit mode and no save button: people who may edit just type, and it is saved.
 */
export function ProjectOverviewEditor({
  project,
  canEdit,
  children,
}: {
  project: Project;
  canEdit: boolean;
  /** Shown between the summary and the description: the row of project properties. */
  children?: React.ReactNode;
}) {
  const router = useRouter();
  const summary = useAutosave((description: string) => updateProject({ id: project.id, changes: { description } }));
  const content = useAutosave((doc: RichDoc | null) => updateProject({ id: project.id, changes: { content: doc } }));
  const status = content.status !== "idle" ? content.status : summary.status;
  const summaryInput = useRemoteValue<HTMLInputElement>(project.description, summary.isDirty);

  return (
    <div className="flex flex-col gap-6">
      {canEdit ? (
        <div data-presence-id="summary" className="-mt-3">
        <input
          ref={summaryInput}
          defaultValue={project.description}
          placeholder={m.summaryPlaceholder}
          aria-label={m.summaryLabel}
          maxLength={500}
          autoComplete="off"
          onChange={(event) => summary.schedule(event.target.value)}
          onBlur={() => void summary.flush()}
          className="block w-full bg-transparent text-[0.9375rem] leading-[1.375rem] text-muted-foreground outline-none placeholder:text-muted-foreground/70 focus:text-foreground"
        />
        </div>
      ) : project.description ? (
        <p className="-mt-3 text-[0.9375rem] leading-[1.375rem] text-muted-foreground">{project.description}</p>
      ) : null}
      {children}
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
            // "/문서" in the description makes a document on this project and leaves a link to it.
            onCreateDocument={
              canEdit
                ? async () => {
                    const document = await createDocument({
                      owner: { projectId: project.id },
                      title: messages.issues.documents.untitled,
                    });
                    router.refresh();
                    return { href: `/doc/${document.id}`, title: document.title };
                  }
                : undefined
            }
          />
        ) : (
          <p className="text-muted-foreground">{m.noDescription}</p>
        )}
      </section>
    </div>
  );
}
