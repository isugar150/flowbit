"use client";

import { Avatar, AvatarFallback, AvatarGroup, AvatarGroupCount, AvatarImage } from "@/components/ui/avatar";
import { personName } from "@/components/person";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { initials } from "@/lib/initials";
import { messages } from "@/messages";
import { useViewers } from "../realtime-provider";

const m = messages.realtime;
const MAX_SHOWN = 4;

/** Who else has this page open, as overlapping avatars; hover or focus one to see the name. */
export function PresenceAvatars() {
  const viewers = useViewers();
  if (viewers.length === 0) return null;
  const shown = viewers.slice(0, MAX_SHOWN);
  const hidden = viewers.slice(MAX_SHOWN);

  return (
    <AvatarGroup role="group" aria-label={m.viewersLabel(viewers.map(personName).join(", "))}>
      {shown.map((viewer) => (
        <Tooltip key={viewer.id}>
          <TooltipTrigger render={<Avatar size="sm" tabIndex={0} className="outline-none focus-visible:ring-3 focus-visible:ring-ring/50" />}>
            {viewer.avatarUrl ? <AvatarImage src={viewer.avatarUrl} alt="" /> : null}
            <AvatarFallback>{initials(personName(viewer))}</AvatarFallback>
          </TooltipTrigger>
          <TooltipContent>{m.viewing(personName(viewer))}</TooltipContent>
        </Tooltip>
      ))}
      {hidden.length > 0 ? (
        <Tooltip>
          <TooltipTrigger render={<AvatarGroupCount tabIndex={0} className="text-xs outline-none focus-visible:ring-3 focus-visible:ring-ring/50" />}>
            +{hidden.length}
          </TooltipTrigger>
          <TooltipContent>{m.viewing(hidden.map(personName).join(", "))}</TooltipContent>
        </Tooltip>
      ) : null}
    </AvatarGroup>
  );
}
