"use client";

import { useState, useSyncExternalStore } from "react";
import { formatDueDate } from "@/features/issues/meta";
import { timeAgo } from "@/features/projects/meta";

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

/** This year in the browser; null while the server renders, which may not read the clock. */
export function useThisYear(): number | null {
  return useSyncExternalStore(
    subscribe,
    () => new Date().getFullYear(),
    () => null,
  );
}

/** Today as YYYY-MM-DD in the browser's time zone; null while the server renders. */
export function useToday(): string | null {
  return useSyncExternalStore(
    subscribe,
    () => new Intl.DateTimeFormat("sv-SE").format(new Date()),
    () => null,
  );
}

/** "3분 전", "어제". The server sends the plain date; the browser turns it into words. */
export function TimeAgo({ iso }: { iso: string }) {
  const inBrowser = useInBrowser();
  return <time dateTime={iso}>{inBrowser ? timeAgo(iso) : iso.slice(0, 10)}</time>;
}

/** A date without its year when it is this year: 10-24. The server sends the full date. */
export function DueDate({ value }: { value: string }) {
  const inBrowser = useInBrowser();
  return <>{inBrowser ? formatDueDate(value) : value}</>;
}
