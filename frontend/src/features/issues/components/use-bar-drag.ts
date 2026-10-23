"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { draggedDates, sameDates, type BarDragMode, type CalendarIssue, type IssueDates } from "./calendar-dates";

/** How far the pointer must move before pressing a bar becomes a drag, so that a plain click still opens the issue. */
const DRAG_START_PX = 4;
/** How long a dropped bar stays where it was dropped when the issue never gets those dates (a refused save). */
const SETTLE_MS = 1500;

/**
 * A bar being dragged: the issue and the dates it is drawn with meanwhile. `settled` once it is
 * dropped, while the save is on its way.
 */
export type BarDrag = { number: number; mode: BarDragMode; dates: IssueDates; settled: boolean };

/** The day cell under the pointer, by the `data-day` the calendar puts on each of them. */
function dayAt(x: number, y: number): string | null {
  return document.elementFromPoint(x, y)?.closest<HTMLElement>("[data-day]")?.dataset.day ?? null;
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
 * Dragging the bars of the calendar with the pointer. The bar is drawn at the dates it is being
 * dragged to (`drag`), so it follows the pointer, grows and shrinks live; letting go saves those
 * dates through `onMove`, and Escape puts it back.
 *
 * Listeners are on the window, not on the bar: the bar is drawn again on other days while it is
 * dragged, and the one under the pointer when it went down no longer exists then. Touch is left
 * to scrolling.
 */
export function useBarDrag<T extends CalendarIssue>(issues: T[], onMove?: (issue: T, dates: IssueDates) => void) {
  const [drag, setDrag] = useState<BarDrag | null>(null);
  const latest = useRef({ issues, onMove });
  useEffect(() => {
    latest.current = { issues, onMove };
  });
  const stop = useRef<(() => void) | null>(null);
  useEffect(() => () => stop.current?.(), []);

  // A dropped bar stays where it was dropped until the issue itself has those dates. The save is
  // shown at once, but not in the same frame as the drop, and the bar must not jump back between.
  if (drag?.settled) {
    const issue = issues.find((item) => item.number === drag.number);
    if (!issue?.dueDate || sameDates({ startDate: issue.startDate, dueDate: issue.dueDate }, drag.dates)) setDrag(null);
  }
  useEffect(() => {
    if (!drag?.settled) return;
    const timer = setTimeout(() => setDrag(null), SETTLE_MS);
    return () => clearTimeout(timer);
  }, [drag]);

  /** Starts dragging `issue` by the day `anchor` of its bar: all of it, or its first or last day. */
  const press = useCallback((event: React.PointerEvent, issue: T, mode: BarDragMode, anchor: string) => {
    if (event.button !== 0 || event.pointerType === "touch" || !issue.dueDate || !latest.current.onMove) return;
    // No text selection and no native link drag from here on.
    event.preventDefault();
    stop.current?.();
    const origin: IssueDates = { startDate: issue.startDate, dueDate: issue.dueDate };
    const from = { x: event.clientX, y: event.clientY };
    let dates = origin;
    let day = anchor;
    let dragging = false;

    const move = (pointer: PointerEvent) => {
      if (!dragging) {
        if (Math.hypot(pointer.clientX - from.x, pointer.clientY - from.y) < DRAG_START_PX) return;
        dragging = true;
        document.body.style.cursor = mode === "move" ? "grabbing" : "ew-resize";
        setDrag({ number: issue.number, mode, dates, settled: false });
      }
      // Outside the calendar the bar stays on the last day it was over.
      const over = dayAt(pointer.clientX, pointer.clientY) ?? day;
      if (over === day) return;
      day = over;
      dates = draggedDates(origin, mode, anchor, day);
      setDrag({ number: issue.number, mode, dates, settled: false });
    };
    const end = (drop: boolean) => {
      stop.current?.();
      if (!dragging) return;
      swallowNextClick();
      const { issues: current, onMove: save } = latest.current;
      const target = current.find((item) => item.number === issue.number);
      if (drop && target && save && !sameDates(dates, origin)) {
        setDrag({ number: issue.number, mode, dates, settled: true });
        save(target, dates);
      } else {
        setDrag(null);
      }
    };
    const up = () => end(true);
    const cancel = () => end(false);
    const key = (keyboard: KeyboardEvent) => {
      if (keyboard.key === "Escape") end(false);
    };
    window.addEventListener("pointermove", move);
    window.addEventListener("pointerup", up);
    window.addEventListener("pointercancel", cancel);
    window.addEventListener("blur", cancel);
    window.addEventListener("keydown", key);
    stop.current = () => {
      window.removeEventListener("pointermove", move);
      window.removeEventListener("pointerup", up);
      window.removeEventListener("pointercancel", cancel);
      window.removeEventListener("blur", cancel);
      window.removeEventListener("keydown", key);
      document.body.style.cursor = "";
      stop.current = null;
    };
  }, []);

  return { drag, press };
}
