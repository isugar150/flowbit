"use client";

import { useState } from "react";
import Link from "next/link";
import { BanIcon, CalendarIcon, GaugeIcon } from "lucide-react";
import { DueDate } from "@/components/dates";
import { PeopleMarks } from "@/components/person";
import type { ProjectSummary } from "@/features/projects/types";
import { cn } from "@/lib/utils";
import { messages } from "@/messages";
import { StatusIcon } from "../meta";
import type { IssueStatusItem } from "../status-defaults";
import { statusChoices } from "../statuses";
import type { Issue } from "../types";
import { ChipSelect } from "./chip-select";
import { LabelPill } from "./label-chip";
import { PriorityMark } from "./issue-row";

const m = messages.issues.board;

/** What a dragged card carries: the number of its issue. */
const DRAG_TYPE = "application/x-flowbit-issue";

function BoardCard({
  issue,
  options,
  movable,
  showEstimate,
  onMove,
}: {
  issue: Issue;
  options: ReturnType<typeof statusChoices>;
  movable: boolean;
  showEstimate: boolean;
  onMove: (number: number, statusId: string) => void;
}) {
  const finished = issue.status === "DONE" || issue.status === "CANCELED";
  return (
    <li
      draggable={movable}
      onDragStart={(event) => {
        event.dataTransfer.setData(DRAG_TYPE, String(issue.number));
        event.dataTransfer.effectAllowed = "move";
      }}
      className={cn(
        "flex flex-col gap-2 rounded-lg border bg-background p-3 text-[0.8125rem] leading-5 shadow-xs",
        movable && "cursor-grab active:cursor-grabbing",
      )}
    >
      <div className="flex items-start gap-1.5">
        <span className="pt-0.5 text-xs leading-4 text-muted-foreground tabular-nums">{issue.key}</span>
        {issue.triage ? <span className="rounded-full border border-dashed px-1.5 text-xs leading-4 text-muted-foreground">{messages.triage.badge}</span> : null}
        <span className="ml-auto flex items-center gap-1">
          <PriorityMark priority={issue.priority} />
          {/* The same move as the drag, for the keyboard and for a touch screen. */}
          {movable ? <ChipSelect iconOnly label={messages.issues.statusOf(issue.key)} value={issue.statusId} options={options} onChange={(statusId) => onMove(issue.number, statusId)} /> : null}
        </span>
      </div>
      <Link
        href={`/issue/${issue.key}`}
        draggable={false}
        className={cn("line-clamp-3 rounded-sm font-medium break-words outline-none focus-visible:ring-3 focus-visible:ring-ring/50", finished && "text-muted-foreground line-through")}
      >
        {issue.title}
      </Link>
      {issue.parent ? <span className="truncate text-xs leading-4 text-muted-foreground">{m.subOf(issue.parent.key)}</span> : null}
      {issue.labels.length > 0 ? (
        <div className="flex flex-wrap gap-1">
          {issue.labels.map((label) => (
            <LabelPill key={label.id} label={label} />
          ))}
        </div>
      ) : null}
      <div className="flex items-center gap-2 text-xs leading-4 text-muted-foreground">
        {issue.openBlockers > 0 ? (
          <span className="flex items-center gap-1 text-destructive" title={messages.issues.relations.blockedTitle(issue.openBlockers)}>
            <BanIcon className="size-3.5" aria-hidden />
            {messages.issues.relations.blocked}
          </span>
        ) : null}
        {issue.dueDate ? (
          <span className="flex items-center gap-1 tabular-nums">
            <CalendarIcon className="size-3.5" aria-hidden />
            <DueDate value={issue.dueDate} />
          </span>
        ) : null}
        {showEstimate && issue.estimate !== null ? (
          <span className="flex items-center gap-1 tabular-nums" title={messages.issues.estimate}>
            <GaugeIcon className="size-3.5" aria-hidden />
            {messages.issues.estimatePoints(issue.estimate)}
          </span>
        ) : null}
        {issue.assignees.length > 0 ? (
          <span className="ml-auto">
            <PeopleMarks people={issue.assignees} max={3} />
          </span>
        ) : null}
      </div>
    </li>
  );
}

/**
 * The issues as a board (docs/features/board.md): one column for each status, in the order of the workflow. A card is moved to
 * another column by dragging it, or with the status button on the card (the keyboard, a touch screen). The board shows what the
 * list shows: the same filters, search and "completed" window.
 */
export function IssueBoard({
  issues,
  statuses,
  projects,
  canMove,
  onMove,
  hiddenFinished,
}: {
  issues: Issue[];
  statuses: IssueStatusItem[];
  projects: ProjectSummary[];
  canMove: (issue: Issue) => boolean;
  onMove: (number: number, statusId: string) => void;
  /** How many finished issues the "completed" window leaves off the board. */
  hiddenFinished: number;
}) {
  const [over, setOver] = useState<string | null>(null);
  const options = statusChoices(statuses);
  const estimatesOn = new Set(projects.filter((project) => project.estimatesEnabled).map((project) => project.id));

  return (
    <div className="flex flex-col gap-2">
      <div className="flex snap-x gap-3 overflow-x-auto px-4 pb-4" role="list" aria-label={m.label}>
        {statuses.map((status) => {
          const cards = issues.filter((issue) => issue.statusId === status.id);
          return (
            <section
              key={status.id}
              role="listitem"
              aria-label={`${status.name} ${cards.length}`}
              onDragOver={(event) => {
                if (!event.dataTransfer.types.includes(DRAG_TYPE)) return;
                event.preventDefault();
                event.dataTransfer.dropEffect = "move";
                setOver(status.id);
              }}
              onDragLeave={(event) => {
                if (!event.currentTarget.contains(event.relatedTarget as Node | null)) setOver((current) => (current === status.id ? null : current));
              }}
              onDrop={(event) => {
                event.preventDefault();
                setOver(null);
                const number = Number(event.dataTransfer.getData(DRAG_TYPE));
                const moved = issues.find((issue) => issue.number === number);
                if (moved && moved.statusId !== status.id && canMove(moved)) onMove(number, status.id);
              }}
              className={cn("flex w-72 shrink-0 snap-start flex-col gap-2 rounded-xl bg-muted/50 p-2 transition-colors max-md:w-[85vw]", over === status.id && "bg-muted ring-2 ring-ring/40")}
            >
              <h2 className="flex h-7 items-center gap-2 px-1 text-[0.8125rem] leading-5 font-medium">
                <StatusIcon status={status.category} />
                <span className="truncate">{status.name}</span>
                <span className="font-normal text-muted-foreground tabular-nums">{cards.length}</span>
              </h2>
              <ul className="flex flex-col gap-2">
                {cards.map((issue) => (
                  <BoardCard
                    key={issue.id}
                    issue={issue}
                    options={options}
                    movable={canMove(issue)}
                    showEstimate={issue.project !== null && estimatesOn.has(issue.project.id)}
                    onMove={onMove}
                  />
                ))}
              </ul>
              {cards.length === 0 ? <p className="px-1 py-3 text-xs leading-4 text-muted-foreground">{m.emptyColumn}</p> : null}
            </section>
          );
        })}
      </div>
      {hiddenFinished > 0 ? <p className="px-5 text-[0.8125rem] leading-5 text-muted-foreground">{m.hiddenFinished(hiddenFinished)}</p> : null}
    </div>
  );
}
