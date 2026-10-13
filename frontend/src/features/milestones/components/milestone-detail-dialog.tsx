"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { CalendarIcon, DiamondIcon } from "lucide-react";
import { RichTextEditor, type RichDoc } from "@/components/editor/rich-text-editor";
import { SaveStatus } from "@/components/editor/save-status";
import { Dialog, DialogContent, DialogDescription, DialogTitle } from "@/components/ui/dialog";
import { useAutosave } from "@/lib/use-autosave";
import { cn } from "@/lib/utils";
import { messages } from "@/messages";
import { updateMilestone } from "../api";
import { milestoneProgress, type Milestone } from "../types";

const m = messages.milestones;

/**
 * One milestone opened from the project overview: how far along it is, and a document of its own
 * where the people working on it write down where things stand. Like the project's description
 * there is no edit mode and no save button; people who may edit just type, and it is saved.
 */
export function MilestoneDetailDialog({
  projectId,
  milestone,
  canEdit,
  onClose,
}: {
  projectId: string;
  milestone: Milestone;
  canEdit: boolean;
  onClose: () => void;
}) {
  const router = useRouter();
  const notes = useAutosave((content: RichDoc | null) => updateMilestone({ projectId, id: milestone.id, changes: { content } }));
  const percent = milestoneProgress(milestone);
  const done = milestone.issueCount > 0 && milestone.doneCount === milestone.issueCount;

  return (
    <Dialog
      open
      onOpenChange={(open) => {
        if (open) return;
        // What was typed last is sent before the overview is read again, so the row behind
        // (and the next opening of this dialog) starts from what was written.
        void notes.flush().then(() => router.refresh());
        onClose();
      }}
    >
      {/* Hung from the top rather than centred, so it does not jump as the document grows. */}
      <DialogContent className="top-[6dvh] translate-y-0 gap-0 p-0 sm:max-w-2xl">
        {/* The scroll box is inside, not the dialog itself, so the editor's menus are not clipped. */}
        <div className="flex max-h-[88dvh] min-h-64 flex-col gap-5 overflow-y-auto overscroll-contain rounded-xl p-5 pt-6">
          <div className="flex flex-col gap-3">
            <DialogTitle className="flex items-center gap-2 pr-8 text-lg leading-7 font-semibold">
              <DiamondIcon className={cn("size-4 shrink-0", done ? "fill-primary text-primary" : "text-muted-foreground")} aria-hidden="true" />
              <span className="min-w-0 truncate">{milestone.name}</span>
            </DialogTitle>
            <DialogDescription className="sr-only">{m.detailDescription}</DialogDescription>
            <div className="flex flex-wrap items-center gap-x-4 gap-y-2 text-[0.8125rem] leading-5">
              <span className="flex items-center gap-2">
                <span
                  role="progressbar"
                  aria-valuemin={0}
                  aria-valuemax={100}
                  aria-valuenow={percent}
                  aria-label={m.progress(percent)}
                  className="h-1.5 w-24 overflow-hidden rounded-full bg-muted"
                >
                  <span className="block h-full rounded-full bg-primary" style={{ width: `${percent}%` }} />
                </span>
                <span className="tabular-nums">{percent}%</span>
              </span>
              <Link
                href={`/project/${projectId}/issues?milestone=${milestone.id}`}
                className="rounded-sm text-muted-foreground tabular-nums outline-none hover:text-foreground hover:underline focus-visible:ring-3 focus-visible:ring-ring/50"
              >
                {m.issues(milestone.doneCount, milestone.issueCount)}
              </Link>
              <span className="flex items-center gap-1.5 text-muted-foreground tabular-nums">
                <CalendarIcon className="size-3.5" aria-hidden="true" />
                {milestone.targetDate ?? m.noTarget}
              </span>
            </div>
          </div>
          <section className="flex flex-col gap-2">
            <div className="flex items-center justify-between gap-3">
              <h3 className="text-[0.8125rem] leading-5 font-medium text-muted-foreground">{m.notesTitle}</h3>
              <SaveStatus status={notes.status} />
            </div>
            {canEdit || milestone.content ? (
              <RichTextEditor
                initialContent={milestone.content}
                editable={canEdit}
                placeholder={m.notesPlaceholder}
                ariaLabel={m.notesLabel}
                className="min-h-48"
                onChange={notes.schedule}
                keepLocal={notes.isDirty}
              />
            ) : (
              <p className="text-muted-foreground">{m.noNotes}</p>
            )}
          </section>
        </div>
      </DialogContent>
    </Dialog>
  );
}
