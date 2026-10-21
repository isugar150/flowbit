"use client";

import { useState } from "react";
import { SmilePlusIcon } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
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
 * The emoji on one thing (an issue, a comment, a line of the history) and what pressing one does.
 * A press shows right away; the names behind each count come with the saved list. `send` puts the
 * person's emoji on the thing or takes it back.
 */
export function useReactions(saved: Reaction[], send: (emoji: string) => Promise<Reaction[]>) {
  const mutation = useOptimisticMutation(
    saved,
    (current, { emoji }: { emoji: string }) => toggled(current, emoji),
    ({ emoji }: { emoji: string }) => send(emoji),
  );
  return { reactions: mutation.value, toggle: (emoji: string) => mutation.mutate({ emoji }) };
}

/** The emoji already chosen, each with how many people chose it; pressing one adds or takes back your own. */
export function ReactionPills({
  reactions,
  onToggle,
  readOnly = false,
  small = false,
}: {
  reactions: Reaction[];
  onToggle: (emoji: string) => void;
  readOnly?: boolean;
  /** Smaller pills, for comments and lines of the history. */
  small?: boolean;
}) {
  // Not `disabled` when read-only: a disabled button gets no pointer events, and the names behind
  // a count are still worth reading on a deleted issue.
  return reactions.map((reaction) => (
    <Tooltip key={reaction.emoji}>
      <TooltipTrigger
        // Said at once: who chose it is all there is to read here.
        delay={0}
        // An emoji added a moment ago has no names yet (they come with the saved list).
        disabled={reaction.names.length === 0}
        render={
          <button
            type="button"
            aria-label={m.count(reaction.emoji, reaction.count)}
            aria-pressed={reaction.reacted}
            aria-disabled={readOnly || undefined}
            onClick={() => {
              if (!readOnly) onToggle(reaction.emoji);
            }}
            className={cn(
              "flex items-center rounded-full border outline-none hover:bg-muted focus-visible:ring-3 focus-visible:ring-ring/50",
              small ? "h-6 gap-1 px-2 text-xs leading-4 max-md:h-9" : "h-7 gap-1.5 px-2.5 text-[0.8125rem] leading-5 max-md:h-9",
              reaction.reacted && "border-primary/50 bg-primary/10",
              readOnly && "cursor-default",
            )}
          />
        }
      >
        <span aria-hidden="true">{reaction.emoji}</span>
        <span className="tabular-nums">{reaction.count}</span>
      </TooltipTrigger>
      <TooltipContent side="top">{reaction.names.join(", ")}</TooltipContent>
    </Tooltip>
  ));
}

/**
 * The button that opens the list of emoji to choose from. `icon` looks like the other quiet
 * buttons of a comment (edit, delete); the round one stands next to the pills.
 */
export function ReactionPicker({
  onPick,
  variant = "round",
  className,
}: {
  onPick: (emoji: string) => void;
  variant?: "round" | "icon";
  className?: string;
}) {
  const [open, setOpen] = useState(false);
  const trigger =
    variant === "icon" ? (
      <Button type="button" variant="ghost" size="icon-sm" className={cn("text-muted-foreground", className)} aria-label={m.add} />
    ) : (
      <button
        type="button"
        aria-label={m.add}
        className={cn(
          "flex size-7 items-center justify-center rounded-full text-muted-foreground outline-none hover:bg-muted hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50 max-md:size-9",
          className,
        )}
      />
    );
  return (
    <Popover open={open} onOpenChange={setOpen}>
      <Tooltip>
        {/* One button, two jobs: it opens the list, and names itself on hover. */}
        <TooltipTrigger delay={0} render={<PopoverTrigger render={trigger} />}>
          <SmilePlusIcon className="size-4" aria-hidden="true" />
        </TooltipTrigger>
        <TooltipContent side="top">{m.add}</TooltipContent>
      </Tooltip>
      <PopoverContent className="flex w-auto gap-0.5 p-1">
        {REACTIONS.map((emoji) => (
          <button
            key={emoji}
            type="button"
            aria-label={emoji}
            onClick={() => {
              setOpen(false);
              onPick(emoji);
            }}
            className="flex size-8 items-center justify-center rounded-md text-base outline-none hover:bg-accent focus-visible:bg-accent max-md:size-10"
          >
            {emoji}
          </button>
        ))}
      </PopoverContent>
    </Popover>
  );
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
  const { reactions, toggle } = useReactions(saved, (emoji) => toggleReaction({ number, emoji }));
  if (readOnly && reactions.length === 0) return null;

  return (
    <div data-presence-id="reactions" className="flex flex-wrap items-center gap-1.5">
      <ReactionPills reactions={reactions} onToggle={toggle} readOnly={readOnly} />
      {readOnly ? null : <ReactionPicker onPick={toggle} />}
    </div>
  );
}
