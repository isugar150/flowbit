"use client";

import { useCallback, useEffect, useRef, useState } from "react";

export type SaveStatus = "idle" | "saving" | "saved" | "error";

const dirtyChecks = new Set<() => boolean>();
const savedListeners = new Set<() => void>();

/** Whether anything typed on this page is still on its way to the server. */
export function hasUnsavedEdits(): boolean {
  for (const isDirty of dirtyChecks) if (isDirty()) return true;
  return false;
}

/** Calls `listener` each time a round of saving ends. Returns how to stop. */
export function onEditsSaved(listener: () => void): () => void {
  savedListeners.add(listener);
  return () => {
    savedListeners.delete(listener);
  };
}

/**
 * Saves a value a moment after the last change, with no save button. Changes made while a save
 * is in flight are sent right after it, always the latest value, so saves never arrive out of
 * order. Whatever is still waiting is sent when the component goes away.
 */
export function useAutosave<T>(save: (value: T) => Promise<unknown>, delayMs = 800) {
  const [status, setStatus] = useState<SaveStatus>("idle");
  const saveRef = useRef(save);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const pending = useRef<{ value: T } | null>(null);
  const inFlight = useRef(false);

  useEffect(() => {
    saveRef.current = save;
  }, [save]);

  const flush = useCallback(async () => {
    if (timer.current) clearTimeout(timer.current);
    timer.current = null;
    if (inFlight.current) return;
    while (pending.current) {
      const { value } = pending.current;
      pending.current = null;
      inFlight.current = true;
      setStatus("saving");
      try {
        await saveRef.current(value);
        setStatus(pending.current ? "saving" : "saved");
      } catch {
        setStatus("error");
      } finally {
        inFlight.current = false;
      }
    }
    for (const listener of savedListeners) listener();
  }, []);

  const schedule = useCallback(
    (value: T) => {
      pending.current = { value };
      if (timer.current) clearTimeout(timer.current);
      timer.current = setTimeout(() => void flush(), delayMs);
    },
    [delayMs, flush],
  );

  useEffect(
    () => () => {
      void flush();
    },
    [flush],
  );

  /** Whether something typed here has not reached the server yet; then a newer server value must not replace it. */
  const isDirty = useCallback(() => timer.current !== null || pending.current !== null || inFlight.current, []);

  useEffect(() => {
    dirtyChecks.add(isDirty);
    return () => {
      dirtyChecks.delete(isDirty);
    };
  }, [isDirty]);

  return { schedule, flush, status, isDirty };
}
