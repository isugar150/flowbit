"use client";

import { useState, useSyncExternalStore } from "react";
import { formatDueDate } from "@/features/issues/meta";
import { timeAgo } from "@/lib/time-ago";

const subscribe = () => () => {};

/**
 * False while the server renders, true in the browser. Text that depends on the current time must
 * wait for the browser: with `cacheComponents`, reading the clock during a server render is an
 * error ("encountered the unstable value Date.now() while prerendering").
 */
export function useInBrowser(): boolean {
  return useSyncExternalStore(
    subscribe,
    () => true,
    () => false,
  );
}

/**
 * When the component was first shown, in milliseconds; null while the server renders (which may not
 * read the clock) and during hydration, so the first browser render matches the server's.
 */
export function useShownAt(): number | null {
  const inBrowser = useInBrowser();
  const [shownAt] = useState(() => (typeof window === "undefined" ? null : Date.now()));
  return inBrowser ? shownAt : null;
}

/** How often the clock is looked at; what a snapshot says decides whether anything is drawn again. */
const CLOCK_TICK_MS = 15_000;

const clockListeners = new Set<() => void>();
let clockTimer: ReturnType<typeof setInterval> | undefined;

const tickClock = () => clockListeners.forEach((listener) => listener());

/**
 * A clock the whole page shares (one timer however many dates are shown): ticks every few seconds,
 * and at once when the tab is looked at again, since a tab in the background gets its timers
 * slowed down or stopped. A component that reads the clock while rendering reads it in a
 * `useSyncExternalStore` snapshot on this, so it is drawn again only when what it shows has changed.
 */
function subscribeClock(listener: () => void): () => void {
  clockListeners.add(listener);
  if (clockListeners.size === 1) {
    clockTimer = setInterval(tickClock, CLOCK_TICK_MS);
    document.addEventListener("visibilitychange", tickClock);
    window.addEventListener("focus", tickClock);
  }
  return () => {
    clockListeners.delete(listener);
    if (clockListeners.size === 0) {
      clearInterval(clockTimer);
      document.removeEventListener("visibilitychange", tickClock);
      window.removeEventListener("focus", tickClock);
    }
  };
}

/** This year in the browser; null while the server renders, which may not read the clock. */
export function useThisYear(): number | null {
  return useSyncExternalStore(
    subscribeClock,
    () => new Date().getFullYear(),
    () => null,
  );
}

/**
 * Today as YYYY-MM-DD in the browser's time zone; null while the server renders. Changes when the
 * day turns, so what is drawn from it (where "today" is) moves on without a reload.
 */
export function useToday(): string | null {
  return useSyncExternalStore(
    subscribeClock,
    () => new Intl.DateTimeFormat("sv-SE").format(new Date()),
    () => null,
  );
}

/** "3분 전", "어제". The server sends the plain date; the browser turns it into words and keeps them up to date. */
export function TimeAgo({ iso }: { iso: string }) {
  const text = useSyncExternalStore(
    subscribeClock,
    () => timeAgo(iso),
    () => iso.slice(0, 10),
  );
  return <time dateTime={iso}>{text}</time>;
}

/** A date without its year when it is this year: 10-24. The server sends the full date. */
export function DueDate({ value }: { value: string }) {
  const text = useSyncExternalStore(
    subscribeClock,
    () => formatDueDate(value),
    () => value,
  );
  return <>{text}</>;
}
