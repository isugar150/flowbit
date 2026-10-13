"use client";

import { useState } from "react";
import { SmilePlusIcon } from "lucide-react";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { useOptimisticMutation } from "@/lib/use-refreshing-mutation";
import { cn } from "@/lib/utils";
import { messages } from "@/messages";
import { toggleReaction } from "../api";
import { REACTIONS, type Reaction } from "../types";

const m = messages.issues.reactions;

/** The reactions once the person's own `emoji` is added, or taken back when it is already there. */
function toggled(reactions: Reaction[], emoji: string): Reaction[] {
  const mine = reactions.find((reaction) => reaction.emoji === emoji);
  if (!mine) return [...reactions, { emoji, count: 1, reacted: true, names: [] }];
  return reactions
    .map((reaction) =>
      reaction === mine ? { ...mine, reacted: !mine.reacted, count: mine.count + (mine.reacted ? -1 : 1) } : reaction,
    )
    .filter((reaction) => reaction.count > 0);
}

/**
 * Emoji on an issue. Each one shows how many people chose it; pressing it adds or takes back your
 * own. Open to everyone who can see the issue. `readOnly` (a deleted issue) only shows them.
 */
export function IssueReactions({
  number,
  reactions: saved,
  readOnly = false,
}: {
  number: number;
  reactions: Reaction[];
  readOnly?: boolean;
}) {
  const [open, setOpen] = useState(false);
  // A press shows right away; the names behind each count come with the saved list.
  const mutation = useOptimisticMutation(
    saved,
    (current, { emoji }: { number: number; emoji: string }) => toggled(current, emoji),
    toggleReaction,
  );
  const reactions = mutation.value;
  const toggle = (emoji: string) => mutation.mutate({ number, emoji });
  if (readOnly && reactions.length === 0) return null;

  return (
    <div data-presence-id="reactions" className="flex flex-wrap items-center gap-1.5">
      {reactions.map((reaction) => (
        <button
          key={reaction.emoji}
          type="button"
          title={reaction.names.join(", ")}
          aria-label={m.count(reaction.emoji, reaction.count)}
          aria-pressed={reaction.reacted}
          disabled={readOnly}
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
      {readOnly ? null : (
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
      )}
    </div>
  );
}
