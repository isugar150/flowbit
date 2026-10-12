"use client";

import { useSyncExternalStore } from "react";
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
