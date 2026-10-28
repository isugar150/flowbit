"use client";

import { useEffect, useRef, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { TimeAgo } from "@/components/dates";
import { HoverCard, HoverCardContent, HoverCardTrigger } from "@/components/ui/hover-card";
import { messages } from "@/messages";
import type { CardActivity } from "../card-activity";
import { PriorityIcon, StatusIcon, priorityLabel, statusLabel } from "../meta";
import { personName } from "@/components/person";
import type { Issue, IssuePriority, IssueStatus } from "../types";

const m = messages.issues.card;

/**
 * What the card over an issue on a calendar says (D-201): its title and properties, where it is and who asked for it, and
 * its last few history entries. Not its text. Each field a page does not have is left out (a shared page has no people on
 * the issue), and `activity` is the entries it already has, or comes later through `IssueHoverCard`.
 */
export type IssueCardData = {
  key: string;
  title: string;
  status: IssueStatus;
  /** What the status is called, when it is not the name of its category. */
  statusName?: string;
  priority?: IssuePriority;
  startDate: string | null;
  dueDate: string | null;
  project?: string | null;
  /** Who made the issue. */
  requester?: string | null;
  assignees?: string[];
  labels?: { name: string; color: string }[];
  activity?: CardActivity[];
};

/** The card over an issue of the signed-in person's own lists: everything the issue says, but its text. */
export function cardOfIssue(issue: Issue): IssueCardData {
  return {
    key: issue.key,
    title: issue.title,
    status: issue.status,
    statusName: issue.statusName,
    priority: issue.priority,
    startDate: issue.startDate,
    dueDate: issue.dueDate,
    project: issue.project?.name ?? null,
    requester: personName(issue.createdBy),
    assignees: issue.assignees.map(personName),
    labels: issue.labels,
  };
}

function Property({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex min-w-0 flex-col gap-0.5">
      <dt className="text-xs leading-4 text-muted-foreground">{label}</dt>
      <dd className="flex min-w-0 flex-wrap items-center gap-1 leading-5 [&>*]:min-w-0">{children}</dd>
    </div>
  );
}

/** The card itself. `activity` null: not asked for or still coming (`loading`), and nothing is said about it. */
export function IssueCard({ card, activity, loading }: { card: IssueCardData; activity: CardActivity[] | null; loading: boolean }) {
  const dates = card.startDate && card.dueDate ? `${card.startDate} ~ ${card.dueDate}` : card.dueDate;
  return (
    <div className="flex flex-col gap-3 text-[0.8125rem] leading-5">
      <div className="flex flex-col gap-0.5">
        <p className="flex items-center gap-1.5 text-xs leading-4 text-muted-foreground">
          <StatusIcon status={card.status} className="size-3.5" />
          <span className="tabular-nums">{card.key}</span>
        </p>
        <h3 className="line-clamp-3 text-sm leading-5 font-medium break-words">{card.title}</h3>
      </div>
      <dl className="grid grid-cols-2 gap-x-3 gap-y-2">
        <Property label={m.status}>{card.statusName ?? statusLabel(card.status)}</Property>
        {card.priority ? (
          <Property label={m.priority}>
            <PriorityIcon priority={card.priority} className="size-3.5" />
            {priorityLabel(card.priority)}
          </Property>
        ) : null}
        {dates ? (
          <Property label={card.startDate ? m.period : m.dueDate}>
            <span className="tabular-nums">{dates}</span>
          </Property>
        ) : null}
        {card.assignees && card.assignees.length > 0 ? <Property label={m.assignees}>{card.assignees.join(", ")}</Property> : null}
        {card.project ? <Property label={m.project}>{card.project}</Property> : null}
        {card.requester ? <Property label={m.requester}>{card.requester}</Property> : null}
        {card.labels && card.labels.length > 0 ? (
          <div className="col-span-2">
            <Property label={m.labels}>
              {card.labels.map((label) => (
                <span key={label.name} className="flex h-5 max-w-36 items-center gap-1.5 rounded-full border px-1.5 text-xs leading-4">
                  <span aria-hidden="true" className="size-2 shrink-0 rounded-full" style={{ backgroundColor: label.color }} />
                  <span className="truncate">{label.name}</span>
                </span>
              ))}
            </Property>
          </div>
        ) : null}
      </dl>
      {activity !== null || loading ? (
        <section aria-label={m.activity} className="flex flex-col gap-1 border-t pt-2">
          <h4 className="text-xs leading-4 font-medium text-muted-foreground">{m.activity}</h4>
          {loading && activity === null ? <p className="text-xs leading-4 text-muted-foreground">{m.loading}</p> : null}
          {activity !== null && activity.length === 0 ? <p className="text-xs leading-4 text-muted-foreground">{m.noActivity}</p> : null}
          {activity !== null && activity.length > 0 ? (
            <ul className="flex flex-col gap-1">
              {activity.map((entry, index) => (
                <li key={`${entry.at}-${index}`} className="text-xs leading-4 text-muted-foreground">
                  {entry.who ? <span className="font-medium text-foreground">{entry.who}</span> : null}
                  {entry.text}
                  <span aria-hidden="true"> · </span>
                  <TimeAgo iso={entry.at} />
                </li>
              ))}
            </ul>
          ) : null}
        </section>
      ) : null}
    </div>
  );
}

/** How long the pointer rests on a bar before its card opens, and how soon the next one opens when another was just put away. */
const OPEN_DELAY_MS = 300;
const NEXT_DELAY_MS = 60;
const NEXT_WINDOW_MS = 500;
/** When a card was last put away (by any bar), so that going from one bar to the next does not wait the whole time again. */
let lastClosedAt = 0;

/**
 * Makes `children` (what the pointer rests on: a bar of the calendar) the trigger of a card. `render` is the element it
 * is drawn as. The card opens after the pointer has rested a moment (at once when another card was put away a moment ago,
 * or the bar is reached by keyboard) and closes **the moment the pointer leaves the bar**, and it does not open at all
 * while `disabled` (a bar being dragged). Its entries come from the card when it has them, else from `load`, asked for
 * when the card first opens and kept for a minute.
 *
 * When it opens and closes is decided here and not by Base UI's own hovering: that keeps a card open while the pointer
 * crosses it on the way to the card (a "safe polygon"), and since the card lets the pointer through (it does not take it, D-203),
 * the bar behind it would open its card while the first was still there: two cards at once.
 */
export function IssueHoverCard({
  render,
  children,
  card,
  load,
  disabled,
  side,
  align,
}: {
  render: React.ReactElement;
  children: React.ReactNode;
  card: IssueCardData;
  load?: () => Promise<CardActivity[]>;
  disabled?: boolean;
  /** Which side of what it is over the card opens on (below unless said; it turns to the other side when there is no room). */
  side?: "top" | "right" | "bottom" | "left";
  align?: "start" | "center" | "end";
}) {
  const [open, setOpen] = useState(false);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const stopTimer = () => {
    if (timer.current) clearTimeout(timer.current);
    timer.current = null;
  };
  useEffect(() => stopTimer, []);
  const show = (at: "pointer" | "keyboard") => {
    stopTimer();
    const delay = at === "keyboard" ? 0 : Date.now() - lastClosedAt < NEXT_WINDOW_MS ? NEXT_DELAY_MS : OPEN_DELAY_MS;
    timer.current = setTimeout(() => setOpen(true), delay);
  };
  const hide = () => {
    stopTimer();
    if (open) lastClosedAt = Date.now();
    setOpen(false);
  };
  const shown = open && !disabled;
  const loaded = useQuery({
    queryKey: ["issue-card-activity", card.key],
    queryFn: () => (load ? load() : Promise.resolve([])),
    enabled: shown && !card.activity && load !== undefined,
    staleTime: 60_000,
    retry: false,
  });
  // A history that could not be read leaves its place out rather than say something wrong.
  const activity = card.activity ?? loaded.data ?? null;
  return (
    <HoverCard
      open={shown}
      // Only what Base UI sees that the bar does not: Escape, and a press elsewhere.
      onOpenChange={(next, details) => {
        if (!next && (details.reason === "escape-key" || details.reason === "outside-press")) hide();
      }}
    >
      <HoverCardTrigger
        render={render}
        onPointerEnter={(event) => {
          if (event.pointerType !== "touch") show("pointer");
        }}
        onPointerLeave={hide}
        // Pressing a bar is for dragging or opening it, not for reading its card.
        onPointerDown={hide}
        onFocus={(event) => {
          // Reached by keyboard; a press also focuses, and that one is not for the card.
          if (event.currentTarget.matches(":focus-visible")) show("keyboard");
        }}
        onBlur={hide}
      >
        {children}
      </HoverCardTrigger>
      <HoverCardContent side={side} align={align}>
        <IssueCard card={card} activity={activity} loading={loaded.isFetching} />
      </HoverCardContent>
    </HoverCard>
  );
}
