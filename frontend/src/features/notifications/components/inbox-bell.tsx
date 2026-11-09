"use client";

import { useRef, useState } from "react";
import Link from "next/link";
import { useInfiniteQuery, useQueryClient } from "@tanstack/react-query";
import { BellIcon, CheckIcon, InfoIcon, RotateCcwIcon, XIcon } from "lucide-react";
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
  const [filter, setFilter] = useState<"pending" | "completed" | "all">("pending");
  const [lastAction, setLastAction] = useState<{ id: string; title: string; completed: boolean } | null>(null);
  const focusUndo = useRef(false);
  const focusFilter = useRef(false);
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
    <Popover open={open} onOpenChange={(nextOpen) => {
      if (nextOpen) setFilter("pending");
      setOpen(nextOpen);
    }}>
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
        className="flex h-[min(40rem,calc(100dvh-4rem))] w-[28rem] max-w-[calc(100vw-1rem)] flex-col gap-0 p-0"
      >
        <div className="flex items-center justify-between gap-2 border-b px-3 py-2">
          <div className="flex min-w-0 flex-col">
            <h2 className="text-sm leading-5 font-medium">{m.title}</h2>
            <p className="text-xs leading-4 text-muted-foreground">{unread > 0 ? m.unread(unread) : m.allRead}</p>
          </div>
          {unread > 0 ? <Button
            type="button"
            variant="ghost"
            size="sm"
            className="shrink-0 max-md:h-11"
            disabled={unread === 0 || read.isPending}
            onClick={() => read.mutate({ all: true })}
          >
            {m.markAllRead}
          </Button> : null}
        </div>
        <div className="flex gap-1 border-b px-3 py-2" role="group" aria-label={m.title}>
          {(["all", "pending", "completed"] as const).map((value) => (
            <Button key={value} variant={filter === value ? "secondary" : "ghost"} size="sm" className="max-md:h-11"
              ref={(node) => {
                if (node && value === filter && focusFilter.current) {
                  node.focus();
                  focusFilter.current = false;
                }
              }}
              aria-pressed={filter === value} onClick={() => setFilter(value)}>{m[value]}</Button>
          ))}
        </div>
        {failure ? (
          <div className="flex items-start gap-2 border-b px-3 py-2">
            <p role="alert" className="min-w-0 flex-1 text-xs leading-5 text-destructive">{apiErrorMessage(failure)}</p>
            <Button variant="ghost" size="icon-sm" aria-label={m.dismissError} className="shrink-0 max-md:size-11" onClick={() => {
              completion.reset();
              read.reset();
              setLastAction(null);
            }}><XIcon /></Button>
          </div>
        ) : null}
        <div className="min-h-0 flex-1 overflow-y-auto">
          {inbox.isPending ? (
            <p className="flex min-h-full items-center justify-center gap-2 px-3 py-6 text-[0.8125rem] leading-5 text-muted-foreground">
              <Spinner aria-hidden="true" />
              {m.loading}
            </p>
          ) : inbox.isError ? (
            <p role="alert" className="flex min-h-full items-center justify-center px-3 py-6 text-[0.8125rem] leading-5 text-destructive">
              {m.failed}
            </p>
          ) : items.length === 0 ? (
            <p className="flex min-h-full items-center justify-center px-3 py-6 text-center text-[0.8125rem] leading-5 text-muted-foreground">{filter === "pending" ? m.emptyPending : filter === "completed" ? m.emptyCompleted : m.empty}</p>
          ) : (
            <ul className="flex flex-col divide-y">
              {items.map((item) => (
                <li key={item.id} className="flex flex-col">
                  <Notification
                    item={item}
                    onOpen={() => {
                      if (!item.read) read.mutate({ ids: [item.id] });
                      setOpen(false);
                    }}
                  />
                  <div className="mx-3 flex min-h-11 items-center justify-between gap-3 border-t border-border/50 py-1.5">
                    <span className={cn("inline-flex items-center gap-1 rounded-md px-2 py-1 text-xs leading-4", item.actionable && !item.completed ? "bg-primary/10 font-medium text-primary" : "text-muted-foreground")}>
                      {item.actionable ? (item.completed ? <CheckIcon className="size-3.5" aria-hidden="true" /> : null) : <InfoIcon className="size-3.5" aria-hidden="true" />}
                      {item.actionable ? (item.completed ? m.completed : m.pending) : m.information}
                    </span>
                    {item.actionable ? <Button type="button" variant="ghost" size="sm" className="shrink-0 max-md:h-11"
                    aria-label={item.completed ? m.restore : m.complete}
                    onClick={(event) => {
                      focusUndo.current = event.detail === 0;
                      completion.mutate({ id: item.id, completed: !item.completed });
                      setLastAction({ id: item.id, title: item.title, completed: !item.completed });
                    }}>
                    {item.completed ? <RotateCcwIcon data-icon="inline-start" /> : <CheckIcon data-icon="inline-start" />}
                    {item.completed ? m.undo : m.complete}
                  </Button> : null}
                  </div>
                </li>
              ))}
            </ul>
          )}
          {inbox.hasNextPage ? (
            <Button variant="ghost" size="sm" className="my-2 w-full max-md:h-11" disabled={inbox.isFetchingNextPage}
              onClick={() => void inbox.fetchNextPage()}>{inbox.isFetchingNextPage ? m.loading : m.more}</Button>
          ) : null}
        </div>
        <div className="flex min-h-20 shrink-0 items-center gap-2 border-t bg-muted/30 px-3 py-2">
          {lastAction && !completion.isError ? (
            <>
              <div className="min-w-0 flex-1 text-xs leading-5" role="status">
                <p className="truncate font-medium" title={lastAction.title}>{lastAction.title}</p>
                <p className="text-muted-foreground">{lastAction.completed ? m.completedNotice : m.restoredNotice}</p>
              </div>
              <Button variant="outline" size="sm" className="shrink-0 max-md:h-11"
                ref={(node) => {
                  if (node && focusUndo.current) {
                    node.focus();
                    focusUndo.current = false;
                  }
                }}
                onClick={(event) => {
                  focusFilter.current = event.detail === 0;
                  completion.mutate({ id: lastAction.id, completed: !lastAction.completed });
                  setLastAction(null);
                }}><RotateCcwIcon data-icon="inline-start" />{m.undo}</Button>
            </>
          ) : <p className="text-xs leading-5 text-muted-foreground">{m.processingHint}</p>}
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
        <span className={cn("line-clamp-2 break-words text-foreground", !item.read && "font-medium")}>{item.title}</span>
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
        </span>
      </span>
    </Link>
  );
}
