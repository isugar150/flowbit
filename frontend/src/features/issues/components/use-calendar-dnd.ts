"use client";

import { useEffect, useRef, useState } from "react";
import { MouseSensor, useDraggable, useSensor, useSensors, type DragMoveEvent, type DragStartEvent } from "@dnd-kit/core";
import { dndAccessibility } from "@/lib/dnd";
import { draggedDates, placedDates, sameDates, type BarDragMode, type CalendarIssue, type IssueDates } from "./calendar-dates";

/** How far the pointer must move before pressing a bar becomes a drag, so that a plain click still opens the issue. */
const DRAG_START_PX = 4;
/** How long a dropped bar stays where it was dropped when the issue never gets those dates (a refused save). */
const SETTLE_MS = 1500;

/**
 * A bar being dragged: the issue and the dates it is drawn with meanwhile (none while an issue that has no date is dragged and the
 * pointer is not over a day). `settled` once it is dropped, while the save is on its way.
 */
export type BarDrag = { number: number; mode: BarDragMode; dates: IssueDates | null; settled: boolean };

/** The days being marked by dragging across the empty part of the calendar, to make an issue over them. */
export type Marking = { from: string; to: string };

/** What a draggable of the calendar says about itself when it is picked up. */
type BarData = {
  kind: "bar";
  number: number;
  mode: BarDragMode;
  /** The day of the bar that was pressed. */
  anchor: string;
  /** Called once the press has become a drag: the list of a day closes then, so that the days under it can be reached. */
  onStart?: () => void;
};
type CreateData = { kind: "create"; anchor: string };
type CalendarDragData = BarData | CreateData;

/** The day cell under the pointer, by the `data-day` the calendar puts on each of them. */
function dayAt(x: number, y: number): string | null {
  return document.elementFromPoint(x, y)?.closest<HTMLElement>("[data-day]")?.dataset.day ?? null;
}

/** Where the pointer is now: where it went down plus how far dnd-kit has seen it move (mouse only; touch is left to scrolling). */
function pointerOf(event: DragMoveEvent): { x: number; y: number } {
  const down = event.activatorEvent as MouseEvent;
  return { x: down.clientX + event.delta.x, y: down.clientY + event.delta.y };
}

/** The click that follows a drag which ends over the bar it started on must not open the issue. */
function swallowNextClick() {
  const swallow = (event: MouseEvent) => {
    event.preventDefault();
    event.stopPropagation();
  };
  window.addEventListener("click", swallow, { capture: true, once: true });
  // Without a click after all, the listener must not wait for an unrelated one.
  setTimeout(() => window.removeEventListener("click", swallow, { capture: true }), 0);
}

/**
 * Dragging in the calendar, with dnd-kit: the bars of the issues (the whole bar, or its first or last day), the issues listed
 * under the calendar that have no date (`place`), the lines of a day's list, and the empty part of the days (marking days to
 * make an issue over them). One `DndContext` for all of it: spread `dndProps` on it. The things that are dragged each call
 * `useBarHandle` or `useCreateHandle`.
 *
 * The bar is drawn at the dates it is being dragged to (`drag`), so it follows the pointer, grows and shrinks live; letting go
 * saves those dates through `onMove`, and Escape puts it back. An issue with no date is dragged the same way: its bar is drawn on
 * the day under the pointer and it gets that day (D-205). Which day the pointer is over is read from the page, not from dnd-kit's
 * drop targets: the bar is drawn again on other days while it is dragged, and the element that was pressed may no longer exist.
 * Touch is left to scrolling (the sensor is the mouse's).
 */
export function useCalendarDnd<T extends CalendarIssue>(
  issues: T[],
  onMove?: (issue: T, dates: IssueDates) => void,
  onCreate?: (dates: IssueDates) => void,
) {
  const [drag, setDrag] = useState<BarDrag | null>(null);
  const [marking, setMarking] = useState<Marking | null>(null);
  const latest = useRef({ issues, onMove, onCreate });
  useEffect(() => {
    latest.current = { issues, onMove, onCreate };
  });
  // What is being dragged, read by the move and the drop.
  const live = useRef<{ data: CalendarDragData; origin: IssueDates | null; dates: IssueDates | null; day: string | null } | null>(null);
  const sensors = useSensors(useSensor(MouseSensor, { activationConstraint: { distance: DRAG_START_PX } }));

  // A dropped bar stays where it was dropped until the issue itself has those dates. The save is
  // shown at once, but not in the same frame as the drop, and the bar must not jump back between.
  if (drag?.settled) {
    const issue = issues.find((item) => item.number === drag.number);
    // An issue that was dropped on a day has no due date until its save is shown: it is not yet "the same".
    const saved = issue?.dueDate && drag.dates && sameDates({ startDate: issue.startDate, dueDate: issue.dueDate }, drag.dates);
    if (!issue || saved) setDrag(null);
  }
  useEffect(() => {
    if (!drag?.settled) return;
    const timer = setTimeout(() => setDrag(null), SETTLE_MS);
    return () => clearTimeout(timer);
  }, [drag]);

  const onDragStart = (event: DragStartEvent) => {
    const data = event.active.data.current as CalendarDragData | undefined;
    if (!data) return;
    if (data.kind === "create") {
      live.current = { data, origin: null, dates: null, day: data.anchor };
      document.body.style.cursor = "cell";
      setMarking({ from: data.anchor, to: data.anchor });
      return;
    }
    const issue = latest.current.issues.find((item) => item.number === data.number);
    if (!issue) return;
    const origin: IssueDates | null = issue.dueDate ? { startDate: issue.startDate, dueDate: issue.dueDate } : null;
    const dates = data.mode === "place" ? null : origin;
    live.current = { data, origin, dates, day: data.mode === "place" ? null : data.anchor };
    document.body.style.cursor = data.mode === "start" || data.mode === "end" ? "ew-resize" : "grabbing";
    setDrag({ number: data.number, mode: data.mode, dates, settled: false });
    data.onStart?.();
  };

  const onDragMove = (event: DragMoveEvent) => {
    const state = live.current;
    if (!state) return;
    const pointer = pointerOf(event);
    const over = dayAt(pointer.x, pointer.y);
    const { data } = state;
    if (data.kind === "create") {
      if (!over || over === state.day) return;
      state.day = over;
      setMarking({ from: data.anchor, to: over });
      return;
    }
    if (data.mode === "place") {
      // Off the calendar the issue is not on a day: it is drawn nowhere, and dropped there it is not placed.
      if (over === state.day) return;
      state.day = over;
      state.dates = over ? placedDates(over) : null;
      setDrag({ number: data.number, mode: data.mode, dates: state.dates, settled: false });
      return;
    }
    // Outside the calendar the bar stays on the last day it was over.
    const target = over ?? state.day;
    if (!target || target === state.day || !state.origin) return;
    state.day = target;
    state.dates = draggedDates(state.origin, data.mode, data.anchor, target);
    setDrag({ number: data.number, mode: data.mode, dates: state.dates, settled: false });
  };

  const finish = (drop: boolean) => {
    const state = live.current;
    live.current = null;
    document.body.style.cursor = "";
    if (!state) return;
    swallowNextClick();
    const { issues: current, onMove: save, onCreate: create } = latest.current;
    const { data } = state;
    if (data.kind === "create") {
      setMarking(null);
      if (!drop) return;
      const to = state.day ?? data.anchor;
      const [first, last] = data.anchor <= to ? [data.anchor, to] : [to, data.anchor];
      create?.({ startDate: first === last ? null : first, dueDate: last });
      return;
    }
    const target = current.find((item) => item.number === data.number);
    if (drop && target && save && state.dates && !(state.origin && sameDates(state.dates, state.origin))) {
      setDrag({ number: data.number, mode: data.mode, dates: state.dates, settled: true });
      save(target, state.dates);
    } else {
      setDrag(null);
    }
  };

  return {
    drag,
    marking,
    dndProps: {
      sensors,
      // The calendar is not scrolled by dragging near its edge, as it never was.
      autoScroll: false,
      accessibility: dndAccessibility,
      onDragStart,
      onDragMove,
      onDragEnd: () => finish(true),
      onDragCancel: () => finish(false),
    },
  };
}

/**
 * Makes an element of the calendar a handle for dragging an issue (a bar, one end of it, a line of a day's list, a chip under the
 * calendar). `id` must differ from the id of every other handle on the page. Give the returned `ref` and `listeners` to the element.
 */
export function useBarHandle({
  id,
  number,
  mode,
  anchor,
  disabled,
  onStart,
}: {
  id: string;
  number: number;
  mode: BarDragMode;
  anchor: string;
  disabled: boolean;
  onStart?: () => void;
}) {
  const data: BarData = { kind: "bar", number, mode, anchor, onStart };
  const { setNodeRef, listeners } = useDraggable({ id, disabled, data });
  return { ref: setNodeRef, listeners };
}

/** Makes the empty part of a day a handle for marking days to make an issue over them. */
export function useCreateHandle({ day, disabled }: { day: string; disabled: boolean }) {
  const data: CreateData = { kind: "create", anchor: day };
  const { setNodeRef, listeners } = useDraggable({ id: `create:${day}`, disabled, data });
  return { ref: setNodeRef, listeners };
}
