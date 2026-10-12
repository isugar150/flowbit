"use client";

import { useState } from "react";
import { SmilePlusIcon } from "lucide-react";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { useRefreshingMutation } from "@/lib/use-refreshing-mutation";
import { cn } from "@/lib/utils";
import { messages } from "@/messages";
import { toggleReaction } from "../api";
import { REACTIONS, type Reaction } from "../types";

const m = messages.issues.reactions;

/**
 * Emoji on an issue. Each one shows how many people chose it; pressing it adds or takes back your
 * own. Open to everyone who can see the issue.
 */
export function IssueReactions({ number, reactions }: { number: number; reactions: Reaction[] }) {
  const [open, setOpen] = useState(false);
  const mutation = useRefreshingMutation(toggleReaction);
  const toggle = (emoji: string) => mutation.mutate({ number, emoji });

  return (
    <div data-presence-id="reactions" className="flex flex-wrap items-center gap-1.5">
      {reactions.map((reaction) => (
        <button
          key={reaction.emoji}
          type="button"
          title={reaction.names.join(", ")}
          aria-label={m.count(reaction.emoji, reaction.count)}
          aria-pressed={reaction.reacted}
          disabled={mutation.isPending}
          onClick={() => toggle(reaction.emoji)}
          className={cn(
            "flex h-7 items-center gap-1.5 rounded-full border px-2.5 text-[0.8125rem] leading-5 outline-none hover:bg-muted focus-visible:ring-3 focus-visible:ring-ring/50 max-md:h-9",
            reaction.reacted && "border-primary/50 bg-primary/10",
          )}
        >
          <span aria-hidden="true">{reaction.emoji}</span>
          <span className="tabular-nums">{reaction.count}</span>
        </button>
      ))}
      <Popover open={open} onOpenChange={setOpen}>
        <PopoverTrigger
          render={
            <button
              type="button"
              aria-label={m.add}
              title={m.add}
              className="flex size-7 items-center justify-center rounded-full text-muted-foreground outline-none hover:bg-muted hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50 max-md:size-9"
            />
          }
        >
          <SmilePlusIcon className="size-4" aria-hidden="true" />
        </PopoverTrigger>
        <PopoverContent className="flex w-auto gap-0.5 p-1">
          {REACTIONS.map((emoji) => (
            <button
              key={emoji}
              type="button"
              aria-label={emoji}
              onClick={() => {
                setOpen(false);
                toggle(emoji);
              }}
              className="flex size-8 items-center justify-center rounded-md text-base outline-none hover:bg-accent focus-visible:bg-accent max-md:size-10"
            >
              {emoji}
            </button>
          ))}
        </PopoverContent>
      </Popover>
    </div>
  );
}
