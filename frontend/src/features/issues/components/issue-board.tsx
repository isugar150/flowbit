"use client";

import { createContext, useContext, useRef, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import {
  DndContext,
  DragOverlay,
  MouseSensor,
  TouchSensor,
  pointerWithin,
  rectIntersection,
  useDraggable,
  useDroppable,
  useSensor,
  useSensors,
  type CollisionDetection,
  type DragEndEvent,
  type DragStartEvent,
} from "@dnd-kit/core";
import { ActivityIcon, BanIcon, CalendarIcon, GaugeIcon, MessageSquareIcon } from "lucide-react";
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

/** What a press on a card leaves to the thing pressed: a link, a button, a menu. The rest of the card opens the issue. */
const OWN_ACTION = "a, button, input, select, textarea, [role=combobox], [role=menuitem], [role=option]";

/** A card is picked up after the mouse has moved this far, so that a plain click still opens the issue. */
const MOUSE_DISTANCE_PX = 6;
/** On a touch screen a card is picked up by holding it, so that touching it to scroll the board still scrolls. */
const TOUCH_HOLD_MS = 250;
const TOUCH_TOLERANCE_PX = 8;

/** The column the pointer is in; where that is not clear (between columns), the one the dragged card overlaps most. */
const inColumn: CollisionDetection = (args) => {
  const within = pointerWithin(args);
  return within.length > 0 ? within : rectIntersection(args);
};

/** Set for a moment after a drag, so that the click which follows a drop does not open the issue under it. */
const DragEndedContext = createContext<{ current: boolean }>({ current: false });

/** What a card shows. Drawn in the column and, the same, under the pointer while the card is dragged. */
function CardBody({
  issue,
  options,
  movable,
  showEstimate,
  showProject,
  onMove,
}: {
  issue: Issue;
  options: ReturnType<typeof statusChoices>;
  movable: boolean;
  showEstimate: boolean;
  /** On a board of more than one project the card says which one it is in, like a line of the list does. */
  showProject: boolean;
  onMove: (number: number, statusId: string) => void;
}) {
  const finished = issue.status === "DONE" || issue.status === "CANCELED";
  const stop = (event: React.SyntheticEvent) => event.stopPropagation();
  return (
    <>
      <div className="flex items-start gap-1.5">
        <span className="shrink-0 pt-0.5 text-xs leading-4 text-muted-foreground tabular-nums">{issue.key}</span>
        {showProject && issue.project ? (
          <span className="min-w-0 truncate pt-0.5 text-xs leading-4 text-muted-foreground" title={issue.project.name}>
            {issue.project.name}
          </span>
        ) : null}
        {issue.triage ? <span className="shrink-0 rounded-full border border-dashed px-1.5 text-xs leading-4 text-muted-foreground">{messages.triage.badge}</span> : null}
        <span className="ml-auto flex shrink-0 items-center gap-1">
          <PriorityMark priority={issue.priority} />
          {/* The same move as the drag, for the keyboard and for a touch screen. Pressing it is not the start of a drag. */}
          {movable ? (
            <span onMouseDown={stop} onTouchStart={stop} onPointerDown={stop}>
              <ChipSelect iconOnly label={messages.issues.statusOf(issue.key)} value={issue.statusId} options={options} onChange={(statusId) => onMove(issue.number, statusId)} />
            </span>
          ) : null}
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
      <div className="flex flex-wrap items-center gap-x-2 gap-y-1 text-xs leading-4 text-muted-foreground">
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
        {/* How many comments and how many changes the issue has, like on a line of the list (D-219); nothing when there are none. */}
        {issue.commentCount > 0 ? (
          <span className="flex items-center gap-1 tabular-nums" title={messages.issues.counts.comments(issue.commentCount)}>
            <MessageSquareIcon className="size-3.5" aria-hidden />
            {issue.commentCount}
            <span className="sr-only">{messages.issues.counts.comments(issue.commentCount)}</span>
          </span>
        ) : null}
        {issue.activityCount > 0 ? (
          <span className="flex items-center gap-1 tabular-nums" title={messages.issues.counts.activity(issue.activityCount)}>
            <ActivityIcon className="size-3.5" aria-hidden />
            {issue.activityCount}
            <span className="sr-only">{messages.issues.counts.activity(issue.activityCount)}</span>
          </span>
        ) : null}
        {issue.assignees.length > 0 ? (
          <span className="ml-auto">
            <PeopleMarks people={issue.assignees} max={3} />
          </span>
        ) : null}
      </div>
    </>
  );
}

const cardClass = "flex flex-col gap-2 rounded-lg border bg-background p-3 text-[0.8125rem] leading-5 shadow-xs select-none";

/**
 * A card in its column. The whole card opens the issue (the title and the buttons on it keep their own action); with the mouse a
 * card is picked up after a short move, on a touch screen after holding it (dnd-kit).
 */
function BoardCard({
  issue,
  options,
  movable,
  showEstimate,
  showProject,
  onMove,
}: {
  issue: Issue;
  options: ReturnType<typeof statusChoices>;
  movable: boolean;
  showEstimate: boolean;
  showProject: boolean;
  onMove: (number: number, statusId: string) => void;
}) {
  const router = useRouter();
  const dragEnded = useContext(DragEndedContext);
  const { setNodeRef, listeners, isDragging } = useDraggable({ id: issue.number, disabled: !movable, data: { number: issue.number } });

  const open = (event: React.MouseEvent<HTMLLIElement>) => {
    const target = event.target as Element;
    // A menu opened from the card lives in a portal: its clicks reach here through React, but not through the document.
    if (dragEnded.current || event.defaultPrevented || event.button !== 0 || !event.currentTarget.contains(target) || target.closest(OWN_ACTION)) return;
    if (window.getSelection()?.toString()) return;
    const href = `/issue/${issue.key}`;
    if (event.metaKey || event.ctrlKey || event.shiftKey) window.open(href, "_blank", "noopener");
    else router.push(href);
  };

  return (
    <li
      ref={setNodeRef}
      {...listeners}
      data-presence-id={`issue-card:${issue.number}`}
      onClick={open}
      className={cn(cardClass, "cursor-pointer", movable && "active:cursor-grabbing", isDragging && "opacity-40")}
    >
      <CardBody issue={issue} options={options} movable={movable} showEstimate={showEstimate} showProject={showProject} onMove={onMove} />
    </li>
  );
}

/** One column: a status, with the cards in it. A card dropped on it gets that status. */
function BoardColumn({ status, count, children }: { status: IssueStatusItem; count: number; children: React.ReactNode }) {
  const { setNodeRef, isOver } = useDroppable({ id: status.id });
  return (
    <section
      ref={setNodeRef}
      role="listitem"
      aria-label={`${status.name} ${count}`}
      className={cn("flex w-72 shrink-0 snap-start flex-col gap-2 rounded-xl bg-muted/50 p-2 transition-colors max-md:w-[85vw]", isOver && "bg-muted ring-2 ring-ring/40")}
    >
      <h2 className="flex h-7 items-center gap-2 px-1 text-[0.8125rem] leading-5 font-medium">
        <StatusIcon status={status.category} statusId={status.id} statusName={status.name} />
        <span className="truncate">{status.name}</span>
        <span className="font-normal text-muted-foreground tabular-nums">{count}</span>
      </h2>
      {children}
    </section>
  );
}

/**
 * The issues as a board (docs/features/board.md): one column for each status, in the order of the workflow. A card is moved to
 * another column by dragging it (dnd-kit), or with the status button on the card (the keyboard, a touch screen). The board shows
 * what the list shows: the same filters, search, order and "completed" window. A press on the card opens the issue.
 */
export function IssueBoard({
  issues,
  statuses,
  projects,
  canMove,
  onMove,
  hiddenFinished,
  showProject,
}: {
  /** Whether the cards say which project they are in: on the board of every project, not on one project's own. */
  showProject: boolean;
  /** In the order the cards are to have inside a column. */
  issues: Issue[];
  statuses: IssueStatusItem[];
  projects: ProjectSummary[];
  canMove: (issue: Issue) => boolean;
  onMove: (number: number, statusId: string) => void;
  /** How many finished issues the "completed" window leaves off the board. */
  hiddenFinished: number;
}) {
  const [active, setActive] = useState<Issue | null>(null);
  const dragEnded = useRef(false);
  const sensors = useSensors(
    useSensor(MouseSensor, { activationConstraint: { distance: MOUSE_DISTANCE_PX } }),
    useSensor(TouchSensor, { activationConstraint: { delay: TOUCH_HOLD_MS, tolerance: TOUCH_TOLERANCE_PX } }),
  );
  const options = statusChoices(statuses);
  const estimatesOn = new Set(projects.filter((project) => project.estimatesEnabled).map((project) => project.id));

  const onDragStart = (event: DragStartEvent) => {
    dragEnded.current = false;
    setActive(issues.find((issue) => issue.number === event.active.data.current?.number) ?? null);
  };
  const onDragEnd = (event: DragEndEvent) => {
    const moved = issues.find((issue) => issue.number === event.active.data.current?.number);
    setActive(null);
    // The click that follows letting go must not open the issue; it is over by the next press.
    dragEnded.current = true;
    setTimeout(() => {
      dragEnded.current = false;
    }, 150);
    const to = event.over ? String(event.over.id) : null;
    if (moved && to && moved.statusId !== to && canMove(moved)) onMove(moved.number, to);
  };

  return (
    <div className="flex flex-col gap-2">
      <DragEndedContext value={dragEnded}>
        <DndContext sensors={sensors} collisionDetection={inColumn} onDragStart={onDragStart} onDragEnd={onDragEnd} onDragCancel={() => setActive(null)}>
          <div className="flex snap-x gap-3 overflow-x-auto px-4 pb-4" role="list" aria-label={m.label}>
            {statuses.map((status) => {
              const cards = issues.filter((issue) => issue.statusId === status.id);
              return (
                <BoardColumn key={status.id} status={status} count={cards.length}>
                  <ul className="flex flex-col gap-2">
                    {cards.map((issue) => (
                      <BoardCard
                        key={issue.id}
                        issue={issue}
                        options={options}
                        movable={canMove(issue)}
                        showEstimate={issue.project !== null && estimatesOn.has(issue.project.id)}
                        showProject={showProject}
                        onMove={onMove}
                      />
                    ))}
                  </ul>
                  {cards.length === 0 ? <p className="px-1 py-3 text-xs leading-4 text-muted-foreground">{m.emptyColumn}</p> : null}
                </BoardColumn>
              );
            })}
          </div>
          {/* What is under the pointer while a card is carried. */}
          <DragOverlay dropAnimation={null}>
            {active ? (
              <div className={cn(cardClass, "w-72 rotate-1 cursor-grabbing shadow-lg ring-2 ring-ring/40 max-md:w-[85vw]")}>
                <CardBody
                  issue={active}
                  options={options}
                  movable={false}
                  showEstimate={active.project !== null && estimatesOn.has(active.project.id)}
                  showProject={showProject}
                  onMove={onMove}
                />
              </div>
            ) : null}
          </DragOverlay>
        </DndContext>
      </DragEndedContext>
      {hiddenFinished > 0 ? <p className="px-5 text-[0.8125rem] leading-5 text-muted-foreground">{m.hiddenFinished(hiddenFinished)}</p> : null}
    </div>
  );
}
