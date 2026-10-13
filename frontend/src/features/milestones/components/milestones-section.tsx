"use client";

import { useState } from "react";
import Link from "next/link";
import dynamic from "next/dynamic";
import { DiamondIcon, EllipsisIcon, FileTextIcon, PlusIcon, Trash2Icon } from "lucide-react";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { FormAlert } from "@/features/auth/components/form-alert";
import { DateChip } from "@/features/issues/components/date-chips";
import { apiErrorMessage } from "@/lib/api/error-message";
import { useOptimisticMutation, useRefreshingMutation } from "@/lib/use-refreshing-mutation";
import { useRemoteValue } from "@/lib/use-remote-value";
import { cn } from "@/lib/utils";
import { messages } from "@/messages";
import { createMilestone, deleteMilestone, updateMilestone } from "../api";
import { milestoneProgress, type Milestone } from "../types";

const m = messages.milestones;
const NAME_MAX_LENGTH = 80;

// Opened on request, so the overview does not carry it until then.
const MilestoneDetailDialog = dynamic(() => import("./milestone-detail-dialog").then((module) => module.MilestoneDetailDialog), { ssr: false });

/** The name field is saved on blur, so while it has focus the person's typing wins. */
const isFocused = (input: HTMLInputElement) => document.activeElement === input;

const iconButtonClass =
  "flex size-7 shrink-0 items-center justify-center rounded-md text-muted-foreground outline-none hover:bg-muted hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50 aria-expanded:bg-muted max-md:size-9";

/**
 * The project's milestones on its overview: each with a progress bar (share of its issues that
 * are done), the issue count linking to those issues, and a target date. People who may edit
 * add, rename, date and delete them in place. Each opens to its own page of notes
 * (milestone-detail-dialog.tsx).
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
      ) : (
        <ul className="flex flex-col rounded-lg border">
          {milestones.map((milestone) => (
            <MilestoneRow
              key={milestone.id}
              projectId={projectId}
              milestone={milestone}
              canEdit={canEdit}
              busy={remove.isPending}
              onRename={(name) => update.mutate({ projectId, id: milestone.id, changes: { name } })}
              onDate={(targetDate) => update.mutate({ projectId, id: milestone.id, changes: { targetDate } })}
              onDelete={() => remove.mutate({ projectId, id: milestone.id })}
              onOpen={() => setOpenId(milestone.id)}
            />
          ))}
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
      {opened ? (
        <MilestoneDetailDialog
          // A different milestone starts with its own document.
          key={opened.id}
          projectId={projectId}
          milestone={opened}
          canEdit={canEdit}
          onClose={() => setOpenId(null)}
        />
      ) : null}
    </section>
  );
}

function MilestoneRow({
  projectId,
  milestone,
  canEdit,
  busy,
  onRename,
  onDate,
  onDelete,
  onOpen,
}: {
  projectId: string;
  milestone: Milestone;
  canEdit: boolean;
  busy: boolean;
  onRename: (name: string) => void;
  onDate: (targetDate: string | null) => void;
  onDelete: () => void;
  onOpen: () => void;
}) {
  const percent = milestoneProgress(milestone);
  const done = milestone.issueCount > 0 && milestone.doneCount === milestone.issueCount;
  const nameInput = useRemoteValue<HTMLInputElement>(milestone.name, isFocused);

  const commitName = (input: HTMLInputElement) => {
    const name = input.value.trim();
    if (!name) input.value = milestone.name;
    else if (name !== milestone.name) onRename(name);
  };

  return (
    <li
      data-presence-id={`milestone:${milestone.id}`}
      className="flex flex-wrap items-center gap-x-3 gap-y-1 border-t px-3 py-2 first:border-t-0"
    >
      <div className="flex min-w-0 flex-1 basis-48 items-center gap-2">
        <DiamondIcon
          className={cn("size-4 shrink-0", done ? "fill-primary text-primary" : "text-muted-foreground")}
          aria-hidden="true"
        />
        {canEdit ? (
          <input
            ref={nameInput}
            defaultValue={milestone.name}
            maxLength={NAME_MAX_LENGTH}
            aria-label={m.nameLabel(milestone.name)}
            autoComplete="off"
            onBlur={(event) => commitName(event.currentTarget)}
            onKeyDown={(event) => {
              if (event.key === "Enter" && !event.nativeEvent.isComposing) event.currentTarget.blur();
              if (event.key === "Escape") {
                event.currentTarget.value = milestone.name;
                event.currentTarget.blur();
              }
            }}
            className="min-w-0 flex-1 truncate rounded-sm bg-transparent font-medium outline-none focus-visible:ring-3 focus-visible:ring-ring/50"
          />
        ) : (
          <span className="min-w-0 flex-1 truncate font-medium">{milestone.name}</span>
        )}
      </div>
      <div className="flex items-center gap-2">
        <div
          role="progressbar"
          aria-valuemin={0}
          aria-valuemax={100}
          aria-valuenow={percent}
          aria-label={m.progress(percent)}
          className="h-1.5 w-20 overflow-hidden rounded-full bg-muted"
        >
          <div className="h-full rounded-full bg-primary transition-[width]" style={{ width: `${percent}%` }} />
        </div>
        <span className="w-9 text-right text-[0.8125rem] leading-5 tabular-nums">{percent}%</span>
        <Link
          href={`/project/${projectId}/issues?milestone=${milestone.id}`}
          aria-label={m.issuesOf(milestone.name)}
          className="w-16 rounded-sm text-[0.8125rem] leading-5 text-muted-foreground tabular-nums outline-none hover:text-foreground hover:underline focus-visible:ring-3 focus-visible:ring-ring/50"
        >
          {m.issues(milestone.doneCount, milestone.issueCount)}
        </Link>
        {canEdit || milestone.targetDate ? (
          <DateChip
            label={m.targetDate}
            placeholder={m.noTargetDate}
            value={milestone.targetDate}
            disabled={!canEdit || busy}
            onChange={onDate}
          />
        ) : null}
        <button type="button" aria-label={m.detailOf(milestone.name)} title={m.detail} className={iconButtonClass} onClick={onOpen}>
          {/* Darker when something is written inside. */}
          <FileTextIcon className={cn("size-4", milestone.content ? "text-foreground" : null)} aria-hidden="true" />
        </button>
        {canEdit ? (
          <DropdownMenu>
            <DropdownMenuTrigger
              render={<button type="button" aria-label={m.actionsOf(milestone.name)} className={iconButtonClass} />}
            >
              <EllipsisIcon className="size-4" aria-hidden="true" />
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end" className="w-56">
              <DropdownMenuGroup>
                <DropdownMenuLabel className="font-normal">{m.deleteHint}</DropdownMenuLabel>
                <DropdownMenuItem variant="destructive" disabled={busy} onClick={onDelete}>
                  <Trash2Icon />
                  {m.delete}
                </DropdownMenuItem>
              </DropdownMenuGroup>
            </DropdownMenuContent>
          </DropdownMenu>
        ) : null}
      </div>
    </li>
  );
}
