"use client";

import { useState } from "react";
import Link from "next/link";
import { ArrowRightIcon, CircleCheckIcon, DiamondIcon } from "lucide-react";
import { useThisYear, useToday } from "@/components/dates";
import { ProjectStatusIcon } from "@/features/projects/meta";
import { cn } from "@/lib/utils";
import { messages } from "@/messages";
import { hasOutline, milestoneOutline } from "../outline";
import { isMilestoneClosed, milestoneProgress, milestoneSummary, shortDate, type Milestone } from "../types";
import { OutlineView, milestoneHref } from "./milestone-card";

const m = messages.milestones;

/**
 * The milestones of the project a ticket list is filtered to, in a row above the list (D-136):
 * name, progress and target date. Pressing one filters the list to it, pressing it again clears
 * the filter. Done ones are folded behind "완료 N개".
 */
export function MilestoneStrip({
  projectId,
  milestones,
  selected,
  onSelect,
}: {
  projectId: string;
  milestones: Milestone[];
  /** The milestone the list is filtered to, if any. */
  selected: string | null;
  onSelect: (id: string | null) => void;
}) {
  // Completed and canceled ones are folded away (D-161).
  const done = milestones.filter(isMilestoneClosed);
  const open = milestones.filter((milestone) => !isMilestoneClosed(milestone));
  // A closed milestone that is the filter stays in view.
  const [showDone, setShowDone] = useState(() => done.some((milestone) => milestone.id === selected));
  // Overdue and "this year" depend on the clock, which only the browser may read while rendering.
  const now = useToday();
  const thisYear = useThisYear();
  if (milestones.length === 0) return null;

  const chip = (milestone: Milestone) => {
    const isDone = isMilestoneClosed(milestone);
    const pressed = milestone.id === selected;
    const percent = milestoneProgress(milestone);
    const overdue = !isDone && milestone.targetDate !== null && now !== null && milestone.targetDate < now;
    return (
      <button
        key={milestone.id}
        type="button"
        aria-pressed={pressed}
        aria-label={`${m.filterBy(milestone.name)} (${milestoneSummary(milestone, thisYear)})`}
        title={[milestoneSummary(milestone, thisYear), milestoneOutline(milestone.content).purpose].filter(Boolean).join(" — ")}
        onClick={() => onSelect(pressed ? null : milestone.id)}
        className={cn(
          "flex h-8 max-w-64 shrink-0 items-center gap-2 rounded-lg border px-2.5 text-[0.8125rem] leading-5 outline-none hover:bg-accent focus-visible:ring-3 focus-visible:ring-ring/50 max-md:h-10",
          pressed ? "border-foreground/40 bg-accent" : "bg-background",
          isDone && !pressed && "text-muted-foreground",
        )}
      >
        {isDone ? (
          <ProjectStatusIcon status={milestone.status} className="size-3.5" />
        ) : (
          <DiamondIcon className="size-3.5 shrink-0 text-warning" aria-hidden="true" />
        )}
        <span className="min-w-0 truncate font-medium">{milestone.name}</span>
        <span aria-hidden="true" className="h-1 w-10 shrink-0 overflow-hidden rounded-full bg-muted">
          <span className="block h-full rounded-full bg-primary" style={{ width: `${percent}%` }} />
        </span>
        <span aria-hidden="true" className="shrink-0 text-xs text-muted-foreground tabular-nums">
          {percent}%
        </span>
        {milestone.targetDate ? (
          <span aria-hidden="true" className={cn("shrink-0 text-xs tabular-nums", overdue ? "text-destructive" : "text-muted-foreground")}>
            {shortDate(milestone.targetDate, thisYear)}
          </span>
        ) : null}
      </button>
    );
  };

  const chosen = milestones.find((milestone) => milestone.id === selected);
  const outline = chosen ? milestoneOutline(chosen.content) : null;

  return (
    <>
    <div role="group" aria-label={m.stripLabel} className="flex items-center gap-1.5 overflow-x-auto overscroll-x-contain px-4 pb-2 [scrollbar-width:thin]">
      {open.map(chip)}
      {showDone ? done.map(chip) : null}
      {done.length > 0 ? (
        <button
          type="button"
          aria-expanded={showDone}
          onClick={() => setShowDone((current) => !current)}
          className="flex h-8 shrink-0 items-center gap-1.5 rounded-lg px-2 text-[0.8125rem] leading-5 text-muted-foreground outline-none hover:bg-accent hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50 max-md:h-10"
        >
          <CircleCheckIcon className="size-3.5" aria-hidden="true" />
          {showDone ? m.hideDone : m.showDone(done.length)}
        </button>
      ) : null}
    </div>
    {/* The milestone the list is filtered to, in short: what it is for and what finishes it (D-137). */}
    {chosen && outline ? (
      <div className="mx-4 mb-2 flex items-start gap-3 rounded-lg border bg-muted/40 px-3 py-2.5">
        {hasOutline(outline) ? (
          <OutlineView outline={outline} className="flex-1" />
        ) : (
          <p className="flex-1 text-[0.8125rem] leading-5 text-muted-foreground">{m.noOutline}</p>
        )}
        <Link
          href={milestoneHref(projectId, chosen.id)}
          className="flex shrink-0 items-center gap-1 rounded-sm text-xs leading-5 font-medium outline-none hover:underline focus-visible:ring-3 focus-visible:ring-ring/50"
        >
          {m.openMilestone}
          <ArrowRightIcon className="size-3.5" aria-hidden="true" />
        </Link>
      </div>
    ) : null}
    </>
  );
}
