"use client";

import { RichTextEditor, type RichDoc } from "@/components/editor/rich-text-editor";
import { SaveStatus } from "@/components/editor/save-status";
import { useAutosave } from "@/lib/use-autosave";
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
  const summary = useAutosave((description: string) => updateProject({ key: project.key, changes: { description } }));
  const content = useAutosave((doc: RichDoc | null) => updateProject({ key: project.key, changes: { content: doc } }));
  const status = content.status !== "idle" ? content.status : summary.status;

  return (
    <div className="flex flex-col gap-6">
      {canEdit ? (
        <input
          defaultValue={project.description}
          placeholder={m.summaryPlaceholder}
          aria-label={m.summaryLabel}
          maxLength={500}
          autoComplete="off"
          onChange={(event) => summary.schedule(event.target.value)}
          onBlur={() => void summary.flush()}
          className="-mt-3 w-full bg-transparent text-[0.9375rem] leading-[1.375rem] text-muted-foreground outline-none placeholder:text-muted-foreground/70 focus:text-foreground"
        />
      ) : project.description ? (
        <p className="-mt-3 text-[0.9375rem] leading-[1.375rem] text-muted-foreground">{project.description}</p>
      ) : null}
      {children}
      <section className="flex flex-col gap-2">
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
          />
        ) : (
          <p className="text-muted-foreground">{m.noDescription}</p>
        )}
      </section>
    </div>
  );
}
