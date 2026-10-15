"use client";

import { useState } from "react";
import dynamic from "next/dynamic";
import { CalendarIcon, ChevronRightIcon, DiamondIcon, EllipsisIcon, PencilIcon, PlusIcon, Trash2Icon } from "lucide-react";
import { DueDate } from "@/components/dates";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { FormAlert } from "@/features/auth/components/form-alert";
import { apiErrorMessage } from "@/lib/api/error-message";
import { useOptimisticMutation, useRefreshingMutation } from "@/lib/use-refreshing-mutation";
import { cn } from "@/lib/utils";
import { messages } from "@/messages";
import { createMilestone, deleteMilestone, updateMilestone } from "../api";
import { isMilestoneDone, milestoneProgress, type Milestone } from "../types";

const m = messages.milestones;
const NAME_MAX_LENGTH = 80;

// Opened on request, so the overview does not carry it (and the editor inside) until then.
const loadDetail = () => import("./milestone-detail-dialog");
const MilestoneDetailDialog = dynamic(() => loadDetail().then((module) => module.MilestoneDetailDialog), { ssr: false });
const preloadDetail = () => void loadDetail();

const iconButtonClass =
  "flex size-7 shrink-0 items-center justify-center rounded-md text-muted-foreground outline-none hover:bg-muted hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50 aria-expanded:bg-muted max-md:size-9";

/**
 * The project's milestones on its overview. Each line only shows: name, progress (share of its
 * issues that are done), issue count and target date; the whole line opens the milestone
 * (milestone-detail-dialog.tsx), where its issues are listed beside a document of notes and where
 * the name and date are changed. The "…" menu holds "편집" (the same dialog) and "삭제". Lines
 * are not edited in place: a field in every line made the list hard to read and easy to change by
 * accident.
 */
export function MilestonesSection({
  projectId,
  milestones: saved,
  canEdit,
}: {
  projectId: string;
  milestones: Milestone[];
  canEdit: boolean;
}) {
  const [adding, setAdding] = useState(false);
  // Done milestones (every ticket done, D-136) are folded away below the open ones.
  const [showDone, setShowDone] = useState(false);
  // After one is added the field starts over empty and stays, so several can be typed in a row.
  const [round, setRound] = useState(0);
  const create = useRefreshingMutation(createMilestone, () => setRound((current) => current + 1));
  // A new name or date shows right away and is saved behind it.
  const update = useOptimisticMutation(
    saved,
    (milestones, { id, changes }: Parameters<typeof updateMilestone>[0]) =>
      milestones.map((milestone) => (milestone.id === id ? { ...milestone, ...changes } : milestone)),
    updateMilestone,
  );
  const milestones = update.value;
  const open = milestones.filter((milestone) => !isMilestoneDone(milestone));
  const done = milestones.filter(isMilestoneDone);
  const row = (milestone: Milestone) => (
    <MilestoneRow
      key={milestone.id}
      milestone={milestone}
      canEdit={canEdit}
      busy={remove.isPending}
      onOpen={() => setOpenId(milestone.id)}
      onDelete={() => remove.mutate({ projectId, id: milestone.id })}
    />
  );
  const remove = useRefreshingMutation(deleteMilestone);
  const error = create.error ?? update.error ?? remove.error;
  const [openId, setOpenId] = useState<string | null>(null);
  const opened = milestones.find((milestone) => milestone.id === openId);

  return (
    <section className="flex flex-col gap-2" aria-labelledby="milestones-title">
      <div className="flex items-center justify-between gap-3">
        <h3 id="milestones-title" className="text-[0.8125rem] leading-5 font-medium text-muted-foreground">
          {m.title}
        </h3>
        {canEdit ? (
          <button type="button" aria-label={m.add} title={m.add} className={iconButtonClass} onClick={() => setAdding(true)}>
            <PlusIcon className="size-4" aria-hidden="true" />
          </button>
        ) : null}
      </div>
      {error ? <FormAlert>{apiErrorMessage(error)}</FormAlert> : null}
      {milestones.length === 0 && !adding ? (
        <p className="text-[0.8125rem] leading-5 text-muted-foreground">{canEdit ? m.empty : m.emptyReadOnly}</p>
      ) : open.length === 0 && !adding ? null : (
        <ul className="flex flex-col overflow-hidden rounded-lg border">
          {open.map(row)}
          {adding ? (
            <li className="flex h-11 items-center gap-2 border-t px-3 first:border-t-0">
              <DiamondIcon className="size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
              <input
                key={round}
                // Appears only after pressing "+", so taking focus is what the person expects.
                autoFocus
                maxLength={NAME_MAX_LENGTH}
                placeholder={m.newPlaceholder}
                aria-label={m.newLabel}
                autoComplete="off"
                disabled={create.isPending}
                onKeyDown={(event) => {
                  if (event.nativeEvent.isComposing) return;
                  if (event.key === "Enter") {
                    event.preventDefault();
                    const name = event.currentTarget.value.trim();
                    if (name) create.mutate({ projectId, name });
                  } else if (event.key === "Escape") {
                    setAdding(false);
                  }
                }}
                onBlur={(event) => {
                  if (!event.currentTarget.value.trim() && !create.isPending) setAdding(false);
                }}
                className="min-w-0 flex-1 bg-transparent font-medium outline-none placeholder:font-normal placeholder:text-muted-foreground"
              />
            </li>
          ) : null}
        </ul>
      )}
      {done.length > 0 ? (
        <button
          type="button"
          aria-expanded={showDone}
          onClick={() => setShowDone((current) => !current)}
          className="flex h-7 w-fit items-center gap-1 rounded-md px-1.5 text-[0.8125rem] leading-5 text-muted-foreground outline-none hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50 max-md:h-10"
        >
          <ChevronRightIcon className={cn("size-3.5", showDone && "rotate-90")} aria-hidden="true" />
          {showDone ? m.doneHide : m.doneToggle(done.length)}
        </button>
      ) : null}
      {/* Done milestones are a list of their own under the toggle, apart from the ones still going. */}
      {showDone && done.length > 0 ? (
        <ul aria-label={m.doneToggle(done.length)} className="flex flex-col overflow-hidden rounded-lg border">
          {done.map(row)}
        </ul>
      ) : null}
      {opened ? (
        <MilestoneDetailDialog
          // A different milestone starts with its own document.
          key={opened.id}
          projectId={projectId}
          milestone={opened}
          canEdit={canEdit}
          onChange={(changes) => update.mutate({ projectId, id: opened.id, changes })}
          onClose={() => setOpenId(null)}
        />
      ) : null}
    </section>
  );
}

function MilestoneRow({
  milestone,
  canEdit,
  busy,
  onOpen,
  onDelete,
}: {
  milestone: Milestone;
  canEdit: boolean;
  busy: boolean;
  onOpen: () => void;
  onDelete: () => void;
}) {
  const percent = milestoneProgress(milestone);
  const done = milestone.issueCount > 0 && milestone.doneCount === milestone.issueCount;

  return (
    <li
      data-presence-id={`milestone:${milestone.id}`}
      className="relative flex items-center gap-3 border-t py-2 pr-2 pl-3 first:border-t-0 hover:bg-muted has-[button[data-open-milestone]:focus-visible]:bg-muted"
    >
      <div className="flex min-w-0 flex-1 flex-col gap-1.5">
        <div className="flex min-w-0 items-center gap-2">
          <DiamondIcon className={cn("size-4 shrink-0", done ? "fill-primary text-primary" : "text-muted-foreground")} aria-hidden="true" />
          {/* The name stretches over the whole line, so any part of it opens the milestone. */}
          <button
            type="button"
            data-open-milestone
            aria-label={m.detailOf(milestone.name)}
            onClick={onOpen}
            onPointerEnter={preloadDetail}
            onFocus={preloadDetail}
            className="min-w-0 truncate text-left font-medium outline-none after:absolute after:inset-0"
          >
            {milestone.name}
          </button>
          {milestone.content ? <span className="shrink-0 text-xs leading-4 text-muted-foreground">{m.hasNotes}</span> : null}
        </div>
        <div className="flex flex-wrap items-center gap-x-3 gap-y-0.5 pl-6 text-[0.8125rem] leading-5 whitespace-nowrap text-muted-foreground">
          <span className="flex items-center gap-2">
            <span
              role="progressbar"
              aria-valuemin={0}
              aria-valuemax={100}
              aria-valuenow={percent}
              aria-label={m.progress(percent)}
              className="h-1.5 w-24 overflow-hidden rounded-full bg-muted-foreground/20 max-sm:w-16"
            >
              <span className="block h-full rounded-full bg-primary transition-[width]" style={{ width: `${percent}%` }} />
            </span>
            <span className="w-9 text-foreground tabular-nums">{percent}%</span>
          </span>
          <span className="tabular-nums">{m.issues(milestone.doneCount, milestone.issueCount)}</span>
          {milestone.targetDate ? (
            <span className="flex items-center gap-1 tabular-nums">
              <CalendarIcon className="size-3.5" aria-hidden="true" />
              <DueDate value={milestone.targetDate} />
            </span>
          ) : null}
        </div>
      </div>
      {canEdit ? (
        <DropdownMenu>
          {/* Above the stretched name button, so it gets its own clicks. */}
          <DropdownMenuTrigger
            render={<button type="button" aria-label={m.actionsOf(milestone.name)} className={cn(iconButtonClass, "relative z-10")} />}
          >
            <EllipsisIcon className="size-4" aria-hidden="true" />
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end" className="w-56">
            <DropdownMenuGroup>
              <DropdownMenuItem onClick={onOpen}>
                <PencilIcon />
                {m.edit}
              </DropdownMenuItem>
            </DropdownMenuGroup>
            <DropdownMenuSeparator />
            <DropdownMenuGroup>
              <DropdownMenuItem variant="destructive" disabled={busy} onClick={onDelete} title={m.deleteHint}>
                <Trash2Icon />
                {m.delete}
              </DropdownMenuItem>
            </DropdownMenuGroup>
          </DropdownMenuContent>
        </DropdownMenu>
      ) : (
        <ChevronRightIcon className="size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
      )}
    </li>
  );
}
