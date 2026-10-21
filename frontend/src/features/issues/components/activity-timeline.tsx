"use client";

import { useState } from "react";
import { Button } from "@/components/ui/button";
import { messages } from "@/messages";
import type { ContentThread } from "@/features/content/types";

const m = messages.issues.activity;

/** One place in the list under the heading "활동": a change, a comment, or a thread on a block. */
export type TimelineEntry<E, C> =
  | { kind: "event"; at: string; event: E }
  | { kind: "comment"; at: string; comment: C; replies: C[] }
  | { kind: "thread"; at: string; thread: ContentThread };

const byTime = (a: { createdAt: string }, b: { createdAt: string }) => (a.createdAt < b.createdAt ? -1 : a.createdAt > b.createdAt ? 1 : 0);

/**
 * History entries, comments and the threads on blocks as one list, **newest first** (D-167); a thread
 * stands where its first comment was written, a comment where it was written. A reply is not an entry
 * of its own: it belongs to its comment, oldest first under it.
 */
export function timelineOf<E extends { createdAt: string }, C extends { id: string; createdAt: string; parentId: string | null }>(
  events: E[],
  comments: C[],
  threads: ContentThread[],
): TimelineEntry<E, C>[] {
  const answers = new Map<string, C[]>();
  for (const comment of comments) {
    if (comment.parentId) answers.set(comment.parentId, [...(answers.get(comment.parentId) ?? []), comment]);
  }
  const entries: TimelineEntry<E, C>[] = [
    ...events.map((event): TimelineEntry<E, C> => ({ kind: "event", at: event.createdAt, event })),
    ...comments
      .filter((comment) => !comment.parentId)
      .map((comment): TimelineEntry<E, C> => ({ kind: "comment", at: comment.createdAt, comment, replies: (answers.get(comment.id) ?? []).sort(byTime) })),
    ...threads.map((thread): TimelineEntry<E, C> => ({ kind: "thread", at: thread.createdAt, thread })),
  ];
  // Timestamps are ISO strings in UTC, so they sort as text; ties keep the order above.
  return entries.sort((a, b) => (a.at < b.at ? 1 : a.at > b.at ? -1 : 0));
}

/** How many entries of the activity show before "이전 활동 N개 더 보기" (D-167). */
export const ACTIVITY_PAGE_SIZE = 15;

/**
 * The newest [ACTIVITY_PAGE_SIZE] entries; the rest wait until the button is pressed, and then they all show.
 * `ShowOlderButton` is that button, below the list.
 */
export function useCollapsedEntries<T>(entries: T[]) {
  const [expanded, setExpanded] = useState(false);
  const hidden = expanded ? 0 : Math.max(0, entries.length - ACTIVITY_PAGE_SIZE);
  return { shown: hidden > 0 ? entries.slice(0, ACTIVITY_PAGE_SIZE) : entries, hidden, expand: () => setExpanded(true) };
}

export function ShowOlderButton({ hidden, onClick }: { hidden: number; onClick: () => void }) {
  if (hidden === 0) return null;
  return (
    <Button type="button" variant="ghost" size="sm" className="w-fit self-center text-muted-foreground max-md:h-11" onClick={onClick}>
      {m.showOlder(hidden)}
    </Button>
  );
}
