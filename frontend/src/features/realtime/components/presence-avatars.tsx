"use client";

import { useQuery } from "@tanstack/react-query";
import { TimeAgo } from "@/components/dates";
import { Avatar, AvatarFallback, AvatarGroup, AvatarGroupCount, AvatarImage } from "@/components/ui/avatar";
import { personName, type PersonLike } from "@/components/person";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { apiFetch } from "@/lib/api/client";
import { initials } from "@/lib/initials";
import { cn } from "@/lib/utils";
import { messages } from "@/messages";
import { usePresencePage, useViewers } from "../realtime-provider";

const m = messages.realtime;
const MAX_SHOWN = 6;
/** Someone who last looked longer ago than this is no longer shown (the user's call, 2026-10-15). */
const RECENT_MS = 30 * 24 * 60 * 60 * 1000;

/** Someone who belongs on the page (GET /api/v1/presence), with when they last had it open. */
type PagePerson = PersonLike & { id: string; lastSeenAt: string | null };

type Shown = PagePerson & { online: boolean };

/**
 * The people of this page who have looked at it, as a row of avatars: the signed-in person, the
 * people who have it open right now first, then the others from whoever looked last, dimmed. Those
 * who have never opened it, or not in the last 30 days, are left out (the user's call, 2026-10-15). Pointing at one (or giving it
 * focus) brings it forward and says at once whether they are looking now or when they last did.
 * The signed-in person comes first.
 *
 * `page` limits it to pages whose key starts with it (`issue:`), so that a frame shown over another
 * page stays empty until its own page has taken over, instead of showing the people of the page below.
 */
export function PresenceAvatars({ page }: { page?: string } = {}) {
  const viewers = useViewers();
  const presence = usePresencePage();
  const userId = presence.userId;
  const pageKey = page === undefined || presence.pageKey?.startsWith(page) ? presence.pageKey : null;
  // Who is here comes over the socket; the rest is read, and read again whenever someone comes
  // or goes, since leaving is what sets a person's time.
  const here = viewers
    .map((viewer) => viewer.id)
    .sort()
    .join(" ");
  const members = useQuery({
    queryKey: ["presence", pageKey, here],
    queryFn: () => apiFetch<PagePerson[]>(`/api/v1/presence?key=${encodeURIComponent(pageKey ?? "")}`),
    enabled: pageKey !== null,
    // The same page keeps its avatars while they are read again; another page starts empty.
    placeholderData: (previous, previousQuery) => (previousQuery?.queryKey[1] === pageKey ? previous : undefined),
  });

  const online = new Set(viewers.map((viewer) => viewer.id));
  // The signed-in person has the page open by definition, so they come first, as looking now.
  const me = (members.data ?? []).find((person) => person.id === userId);
  const people: Shown[] = [
    ...(me ? [{ ...me, lastSeenAt: null, online: true }] : []),
    ...viewers.map((viewer) => ({ ...viewer, lastSeenAt: null, online: true })),
    // Already in the order they last looked. Those who never did, or not in the last 30 days, are not shown.
    ...(members.data ?? [])
      .filter((person) => person.id !== userId && !online.has(person.id) && seenRecently(person.lastSeenAt, members.dataUpdatedAt))
      .map((person) => ({ ...person, online: false })),
  ];
  if (pageKey === null || people.length === 0) return null;
  const shown = people.slice(0, MAX_SHOWN);
  const hidden = people.slice(MAX_SHOWN);

  return (
    // Side by side, not overlapping (the user's call, 2026-10-13: overlapped they could not be told apart).
    <AvatarGroup role="group" className="space-x-0 gap-1" aria-label={viewers.length > 0 ? m.viewersLabel(viewers.map(personName).join(", ")) : m.peopleLabel}>
      {shown.map((person) => (
        <Tooltip key={person.id}>
          <TooltipTrigger
            // Said at once: the avatar is all there is to read here.
            delay={0}
            render={
              <Avatar
                size="sm"
                tabIndex={0}
                aria-label={person.online ? `${personName(person)} · ${m.viewingNow}` : personName(person)}
                className={cn(
                  "transition-[scale,opacity,filter] duration-150 ease-out outline-none hover:z-10 hover:scale-150 focus-visible:z-10 focus-visible:scale-150 focus-visible:ring-3 focus-visible:ring-ring/50 motion-reduce:transition-none",
                  !person.online && "opacity-40 grayscale hover:opacity-100 hover:grayscale-0 focus-visible:opacity-100 focus-visible:grayscale-0",
                )}
              />
            }
          >
            {person.avatarUrl ? <AvatarImage src={person.avatarUrl} alt="" /> : null}
            <AvatarFallback>{initials(personName(person))}</AvatarFallback>
          </TooltipTrigger>
          {/* Below the avatar at its full size. */}
          <TooltipContent sideOffset={12} className="flex flex-col items-center">
            <span className="font-medium">{personName(person)}</span>
            <span className="opacity-80">
              {person.online || !person.lastSeenAt ? (
                m.viewingNow
              ) : (
                <>
                  {m.lastSeen}: <TimeAgo iso={person.lastSeenAt} />
                </>
              )}
            </span>
          </TooltipContent>
        </Tooltip>
      ))}
      {hidden.length > 0 ? (
        <Tooltip>
          <TooltipTrigger
            delay={0}
            render={
              <AvatarGroupCount
                tabIndex={0}
                aria-label={m.morePeople(hidden.length)}
                className="text-xs outline-none focus-visible:ring-3 focus-visible:ring-ring/50"
              />
            }
          >
            +{hidden.length}
          </TooltipTrigger>
          <TooltipContent>{hidden.map(personName).join(", ")}</TooltipContent>
        </Tooltip>
      ) : null}
    </AvatarGroup>
  );
}

/** Whether `lastSeenAt` falls within the 30 days before `now` (when the list was read). */
function seenRecently(lastSeenAt: string | null, now: number): boolean {
  return lastSeenAt !== null && now - Date.parse(lastSeenAt) <= RECENT_MS;
}
