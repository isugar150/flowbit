"use client";

import { useState } from "react";
import { CircleCheckIcon, DiamondIcon } from "lucide-react";
import { cn } from "@/lib/utils";
import { messages } from "@/messages";
import { isMilestoneDone, milestoneProgress, milestoneSummary, shortDate, type Milestone } from "../types";

const m = messages.milestones;

/** Today as YYYY-MM-DD in this browser, to tell an overdue target date. */
function today(): string {
  return new Intl.DateTimeFormat("sv-SE").format(new Date());
}

/**
 * The milestones of the project a ticket list is filtered to, in a row above the list (D-136):
 * name, progress and target date. Pressing one filters the list to it, pressing it again clears
 * the filter. Done ones are folded behind "완료 N개".
 */
export function MilestoneStrip({
  milestones,
  selected,
  onSelect,
}: {
  milestones: Milestone[];
  /** The milestone the list is filtered to, if any. */
  selected: string | null;
  onSelect: (id: string | null) => void;
}) {
  const done = milestones.filter(isMilestoneDone);
  const open = milestones.filter((milestone) => !isMilestoneDone(milestone));
  // A done milestone that is the filter stays in view.
  const [showDone, setShowDone] = useState(() => done.some((milestone) => milestone.id === selected));
  const now = today();
  if (milestones.length === 0) return null;

  const chip = (milestone: Milestone) => {
    const isDone = isMilestoneDone(milestone);
    const pressed = milestone.id === selected;
    const percent = milestoneProgress(milestone);
    const overdue = !isDone && milestone.targetDate !== null && milestone.targetDate < now;
    return (
      <button
        key={milestone.id}
        type="button"
        aria-pressed={pressed}
        aria-label={`${m.filterBy(milestone.name)} (${milestoneSummary(milestone)})`}
        title={milestoneSummary(milestone)}
        onClick={() => onSelect(pressed ? null : milestone.id)}
        className={cn(
          "flex h-8 max-w-64 shrink-0 items-center gap-2 rounded-lg border px-2.5 text-[0.8125rem] leading-5 outline-none hover:bg-accent focus-visible:ring-3 focus-visible:ring-ring/50 max-md:h-10",
          pressed ? "border-foreground/40 bg-accent" : "bg-background",
          isDone && !pressed && "text-muted-foreground",
        )}
      >
        {isDone ? (
          <CircleCheckIcon className="size-3.5 shrink-0 text-primary" aria-hidden="true" />
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
            {shortDate(milestone.targetDate)}
          </span>
        ) : null}
      </button>
    );
  };

  return (
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
  );
}
