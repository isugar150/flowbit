"use client";

import { useState } from "react";
import Link from "next/link";
import { DiamondIcon, EllipsisIcon, PlusIcon, Trash2Icon } from "lucide-react";
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
import { useRefreshingMutation } from "@/lib/use-refreshing-mutation";
import { useRemoteValue } from "@/lib/use-remote-value";
import { cn } from "@/lib/utils";
import { messages } from "@/messages";
import { createMilestone, deleteMilestone, updateMilestone } from "../api";
import { milestoneProgress, type Milestone } from "../types";

const m = messages.milestones;
const NAME_MAX_LENGTH = 80;

/** The name field is saved on blur, so while it has focus the person's typing wins. */
const isFocused = (input: HTMLInputElement) => document.activeElement === input;

const iconButtonClass =
  "flex size-7 shrink-0 items-center justify-center rounded-md text-muted-foreground outline-none hover:bg-muted hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50 aria-expanded:bg-muted max-md:size-9";

/**
 * The project's milestones on its overview: each with a progress bar (share of its issues that
 * are done), the issue count linking to those issues, and a target date. People who may edit
 * add, rename, date and delete them in place.
 */
export function MilestonesSection({
  projectKey,
  milestones,
  canEdit,
}: {
  projectKey: string;
  milestones: Milestone[];
  canEdit: boolean;
}) {
  const [adding, setAdding] = useState(false);
  const create = useRefreshingMutation(createMilestone, () => setAdding(false));
  const update = useRefreshingMutation(updateMilestone);
  const remove = useRefreshingMutation(deleteMilestone);
  const error = create.error ?? update.error ?? remove.error;

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
              projectKey={projectKey}
              milestone={milestone}
              canEdit={canEdit}
              busy={update.isPending || remove.isPending}
              onRename={(name) => update.mutate({ projectKey, id: milestone.id, changes: { name } })}
              onDate={(targetDate) => update.mutate({ projectKey, id: milestone.id, changes: { targetDate } })}
              onDelete={() => remove.mutate({ projectKey, id: milestone.id })}
            />
          ))}
          {adding ? (
            <li className="flex h-11 items-center gap-2 border-t px-3 first:border-t-0">
              <DiamondIcon className="size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
              <input
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
                    if (name) create.mutate({ projectKey, name });
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
    </section>
  );
}

function MilestoneRow({
  projectKey,
  milestone,
  canEdit,
  busy,
  onRename,
  onDate,
  onDelete,
}: {
  projectKey: string;
  milestone: Milestone;
  canEdit: boolean;
  busy: boolean;
  onRename: (name: string) => void;
  onDate: (targetDate: string | null) => void;
  onDelete: () => void;
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
    <li className="flex flex-wrap items-center gap-x-3 gap-y-1 border-t px-3 py-2 first:border-t-0">
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
          href={`/project/${projectKey}/issues?milestone=${milestone.id}`}
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
