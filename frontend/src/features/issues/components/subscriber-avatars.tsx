"use client";

import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { PersonAvatar, personName } from "@/components/person";
import { messages } from "@/messages";
import type { IssuePerson } from "../types";

const m = messages.issues.activity;

/** How many followers get an avatar of their own beside the heading; the rest are behind "+N". */
const MAX_SUBSCRIBER_AVATARS = 5;

/**
 * Who follows the issue: one avatar each, overlapping by about a third. The one under the pointer (or
 * with focus) comes forward and the others part around it (`avatar-fan`, globals.css), and it
 * names its own person: the name, and the login id under it when the name is a display name.
 * Followers past the first few are behind "+N", whose hint lists them.
 */
export function SubscriberAvatars({ people }: { people: IssuePerson[] }) {
  const shown = people.slice(0, MAX_SUBSCRIBER_AVATARS);
  const hidden = people.slice(MAX_SUBSCRIBER_AVATARS);
  return (
    <span role="group" aria-label={m.subscribers(people.map(personName).join(", "))} className="avatar-fan flex items-center -space-x-2">
      {shown.map((person) => (
        <Tooltip key={person.id}>
          <TooltipTrigger
            // Said at once: the avatar is all there is to read here.
            delay={0}
            render={
              <span
                tabIndex={0}
                aria-label={personName(person)}
                // Positioned like the avatar inside it, so that the later one is the one on top.
                className="relative flex rounded-full ring-2 ring-background outline-none focus-visible:ring-3 focus-visible:ring-ring/50"
              />
            }
          >
            <PersonAvatar person={person} />
          </TooltipTrigger>
          <TooltipContent className="flex flex-col items-center">
            <span className="font-medium">{personName(person)}</span>
            {person.displayName ? <span className="opacity-80">{person.loginId}</span> : null}
          </TooltipContent>
        </Tooltip>
      ))}
      {hidden.length > 0 ? (
        <Tooltip>
          <TooltipTrigger
            delay={0}
            render={
              <span
                tabIndex={0}
                aria-label={m.moreSubscribers(hidden.length)}
                className="relative flex size-6 items-center justify-center rounded-full bg-muted text-[0.625rem] leading-none text-muted-foreground ring-2 ring-background outline-none focus-visible:ring-3 focus-visible:ring-ring/50"
              />
            }
          >
            +{hidden.length}
          </TooltipTrigger>
          <TooltipContent className="flex flex-col">
            {hidden.map((person) => (
              <span key={person.id}>
                <span className="font-medium">{personName(person)}</span>
                {person.displayName ? <span className="opacity-80"> · {person.loginId}</span> : null}
              </span>
            ))}
          </TooltipContent>
        </Tooltip>
      ) : null}
    </span>
  );
}
