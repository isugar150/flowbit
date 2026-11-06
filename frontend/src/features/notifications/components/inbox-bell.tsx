"use client";

import { useState } from "react";
import Link from "next/link";
import { useInfiniteQuery, useQueryClient } from "@tanstack/react-query";
import { BellIcon, CheckIcon, RotateCcwIcon } from "lucide-react";
import { TimeAgo } from "@/components/dates";
import { Button } from "@/components/ui/button";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { Spinner } from "@/components/ui/spinner";
import { useOptimisticMutation, useRefreshingMutation } from "@/lib/use-refreshing-mutation";
import { apiErrorMessage } from "@/lib/api/error-message";
import { notifyCommentNavigation } from "@/features/content/use-comment-destination";
import { cn } from "@/lib/utils";
import { messages } from "@/messages";
import { fetchInbox, markInboxRead, setInboxCompleted } from "../api";
import type { InboxItem } from "../types";

const m = messages.inbox;

/** How many lines of a notification the list shows; the rest are told by number. */
const SHOWN_LINES = 3;

/**
 * The bell beside the user menu (D-216): how many notifications the person has not read, and on a press the list of the
 * latest ones. A press on one opens what it is about and marks it read; "모두 읽음" marks them all. `unread` is read by
 * the server with the page, so it follows the realtime notice (`inbox:<id>`) that makes the page read again.
 */
export function InboxBell({ unread, placement }: { unread: number; placement: "sidebar" | "header" }) {
  const [open, setOpen] = useState(false);
  const [filter, setFilter] = useState<"pending" | "completed" | "all">("all");
  const [undoId, setUndoId] = useState<string | null>(null);
  const client = useQueryClient();
  // The count in the key: a notification that comes while the list is open reads it again.
  const inbox = useInfiniteQuery({
    queryKey: ["inbox", unread, filter],
    queryFn: ({ pageParam }) => fetchInbox(filter === "all" ? undefined : filter === "completed", pageParam),
    initialPageParam: 0,
    getNextPageParam: (last, pages) => last.hasMore ? pages.reduce((sum, page) => sum + page.items.length, 0) : undefined,
    enabled: open, staleTime: 0, retry: false,
  });
  const refreshInbox = () => client.invalidateQueries({ queryKey: ["inbox"] });
  const read = useRefreshingMutation(async (input: { ids: string[] } | { all: true }) => {
    await markInboxRead(input);
    await refreshInbox();
  });
  const completion = useOptimisticMutation(
    inbox.data?.pages.flatMap((page) => page.items) ?? [],
    (items, input: { id: string; completed: boolean }) => items.map((item) => item.id === input.id ? { ...item, completed: input.completed } : item),
    async (input: { id: string; completed: boolean }) => {
      await setInboxCompleted(input);
      await refreshInbox();
    },
  );
  const items = completion.value.filter((item) => filter === "all" || (item.actionable && item.completed === (filter === "completed")));
  const failure = completion.error ?? read.error;
  const inSidebar = placement === "sidebar";

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger
        render={
          <Button
            variant="ghost"
            size="icon-sm"
            aria-label={unread > 0 ? m.labelUnread(unread) : m.label}
            className={cn("relative shrink-0 max-md:size-11", inSidebar && "hover:bg-sidebar-accent aria-expanded:bg-sidebar-accent")}
          />
        }
      >
        <BellIcon />
        {unread > 0 ? (
          <span
            aria-hidden="true"
            className="absolute -top-0.5 -right-0.5 flex h-4 min-w-4 items-center justify-center rounded-full bg-primary px-1 text-[0.625rem] leading-none font-medium text-primary-foreground tabular-nums"
          >
            {unread > 99 ? "99+" : unread}
          </span>
        ) : null}
      </PopoverTrigger>
      <PopoverContent
        side={inSidebar ? "top" : "bottom"}
        align={inSidebar ? "end" : "end"}
        className="flex max-h-[min(32rem,calc(100dvh-4rem))] w-96 max-w-[calc(100vw-1rem)] flex-col gap-0 p-0"
      >
        <div className="flex items-center justify-between gap-2 border-b px-3 py-2">
          <div className="flex min-w-0 flex-col">
            <h2 className="text-sm leading-5 font-medium">{m.title}</h2>
            <p className="text-xs leading-4 text-muted-foreground">{unread > 0 ? m.unread(unread) : m.allRead}</p>
          </div>
          <Button
            type="button"
            variant="ghost"
            size="sm"
            className="shrink-0 max-md:h-11"
            disabled={unread === 0 || read.isPending}
            onClick={() => read.mutate({ all: true })}
          >
            {m.markAllRead}
          </Button>
        </div>
        <div className="flex gap-1 border-b px-3 py-2" role="group" aria-label={m.title}>
          {(["pending", "completed", "all"] as const).map((value) => (
            <Button key={value} variant={filter === value ? "secondary" : "ghost"} size="sm" className="max-md:h-11"
              aria-pressed={filter === value} onClick={() => setFilter(value)}>{m[value]}</Button>
          ))}
        </div>
        {undoId && !completion.isError ? (
          <div className="flex items-center justify-between gap-2 border-b px-3 py-2 text-xs" role="status">
            <span>{m.completedNotice}</span>
            <Button variant="ghost" size="sm" className="shrink-0 max-md:h-11" onClick={() => {
              completion.mutate({ id: undoId, completed: false });
              setUndoId(null);
            }}>{m.undo}</Button>
          </div>
        ) : null}
        {failure ? <p role="alert" className="px-3 py-2 text-xs text-destructive">{apiErrorMessage(failure)}</p> : null}
        <div className="min-h-0 flex-1 overflow-y-auto">
          {inbox.isPending ? (
            <p className="flex items-center gap-2 px-3 py-6 text-[0.8125rem] leading-5 text-muted-foreground">
              <Spinner aria-hidden="true" />
              {m.loading}
            </p>
          ) : inbox.isError ? (
            <p role="alert" className="px-3 py-6 text-[0.8125rem] leading-5 text-destructive">
              {m.failed}
            </p>
          ) : items.length === 0 ? (
            <p className="px-3 py-6 text-center text-[0.8125rem] leading-5 text-muted-foreground">{filter === "pending" ? m.emptyPending : filter === "completed" ? m.emptyCompleted : m.empty}</p>
          ) : (
            <ul className="flex flex-col divide-y">
              {items.map((item) => (
                <li key={item.id} className="flex items-start">
                  <Notification
                    item={item}
                    onOpen={() => {
                      if (!item.read) read.mutate({ ids: [item.id] });
                      setOpen(false);
                    }}
                  />
                  {item.actionable ? <Button type="button" variant="ghost" size="icon-sm" className="mt-2 mr-2 shrink-0 max-md:size-11"
                    aria-label={item.completed ? m.restore : m.complete} title={item.completed ? m.restore : m.complete}
                    onClick={() => {
                      completion.mutate({ id: item.id, completed: !item.completed });
                      setUndoId(item.completed ? null : item.id);
                    }}>
                    {item.completed ? <RotateCcwIcon /> : <CheckIcon />}
                  </Button> : null}
                </li>
              ))}
            </ul>
          )}
          {inbox.hasNextPage ? (
            <Button variant="ghost" size="sm" className="my-2 w-full max-md:h-11" disabled={inbox.isFetchingNextPage}
              onClick={() => void inbox.fetchNextPage()}>{inbox.isFetchingNextPage ? m.loading : m.more}</Button>
          ) : null}
        </div>
      </PopoverContent>
    </Popover>
  );
}

function Notification({ item, onOpen }: { item: InboxItem; onOpen: () => void }) {
  const shown = item.lines.slice(0, SHOWN_LINES);
  return (
    <Link
      href={item.path}
      onNavigate={() => notifyCommentNavigation(item.path)}
      onClick={onOpen}
      className={cn(
        "flex min-w-0 flex-1 gap-2 px-3 py-2.5 text-[0.8125rem] leading-5 outline-none hover:bg-accent focus-visible:bg-accent max-md:min-h-11",
        !item.read && "bg-muted/40",
      )}
    >
      <span className="mt-1.5 flex size-2 shrink-0">
        {item.read ? null : (
          <>
            <span aria-hidden="true" className="size-2 rounded-full bg-primary" />
            <span className="sr-only">{m.unreadMark}</span>
          </>
        )}
      </span>
      <span className="flex min-w-0 flex-1 flex-col gap-0.5">
        <span className={cn("truncate", item.read ? "text-muted-foreground" : "font-medium")}>{item.title}</span>
        <span className="line-clamp-2 break-words text-muted-foreground">{item.headline}</span>
        {shown.length > 0 ? (
          <span className="flex flex-col text-xs leading-4 text-muted-foreground">
            {shown.map((line, index) => (
              <span key={index} className="truncate">
                {line}
              </span>
            ))}
            {item.lines.length > SHOWN_LINES ? <span>{m.moreLines(item.lines.length - SHOWN_LINES)}</span> : null}
          </span>
        ) : null}
        <span className="text-xs leading-4 text-muted-foreground">
          <TimeAgo iso={item.createdAt} />
          {item.completed ? <span className="ml-2">{m.completed}</span> : null}
        </span>
      </span>
    </Link>
  );
}
