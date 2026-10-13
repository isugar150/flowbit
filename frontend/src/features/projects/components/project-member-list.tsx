"use client";

import { useState } from "react";
import { XIcon } from "lucide-react";
import { Person, personName } from "@/components/person";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { FormAlert } from "@/features/auth/components/form-alert";
import { apiErrorMessage } from "@/lib/api/error-message";
import { useRefreshingMutation } from "@/lib/use-refreshing-mutation";
import { messages } from "@/messages";
import { removeProjectMember } from "../api";
import type { ProjectMember } from "../types";

const m = messages.projects;

/**
 * The people on the members card of a project overview, each with their role. Those who manage
 * the project take someone out right here: the "x" asks once more in place before it removes.
 * Roles are changed in the project settings, not here (the user's call, 2026-10-13).
 */
export function ProjectMemberList({
  projectId,
  members,
  canManage,
  currentUserId,
}: {
  projectId: string;
  members: ProjectMember[];
  canManage: boolean;
  /** The reader, marked "나" in the list: their own line is where they see their role. */
  currentUserId: string;
}) {
  const [confirmingId, setConfirmingId] = useState<string | null>(null);
  const remove = useRefreshingMutation(removeProjectMember, () => setConfirmingId(null));
  const failure = remove.error;

  return (
    <div className="flex flex-col gap-2 px-1 py-1">
      {failure ? <FormAlert>{apiErrorMessage(failure)}</FormAlert> : null}
      <ul className="flex flex-col gap-2">
        {members.map((member) => {
          const name = personName(member);
          const confirming = confirmingId === member.userId;
          return (
            <li key={member.userId} className="flex min-h-8 items-center justify-between gap-2">
              <span className="flex min-w-0 items-center gap-1.5">
                <Person person={member} />
                {member.userId === currentUserId ? <Badge variant="secondary">{messages.members.you}</Badge> : null}
              </span>
              {confirming ? (
                <span className="flex shrink-0 items-center gap-1">
                  <Button
                    type="button"
                    variant="destructive"
                    size="sm"
                    className="max-md:h-11"
                    aria-label={m.removeOf(name)}
                    disabled={remove.isPending}
                    onClick={() => remove.mutate({ id: projectId, userId: member.userId })}
                  >
                    {m.remove}
                  </Button>
                  <Button
                    type="button"
                    variant="ghost"
                    size="sm"
                    className="max-md:h-11"
                    disabled={remove.isPending}
                    onClick={() => setConfirmingId(null)}
                  >
                    {m.removeCancel}
                  </Button>
                </span>
              ) : (
                <span className="flex shrink-0 items-center gap-1">
                  <span className="text-[0.8125rem] leading-5 text-muted-foreground">{messages.roles[member.role]}</span>
                  {canManage ? (
                    <Button
                      type="button"
                      variant="ghost"
                      size="icon-sm"
                      className="text-muted-foreground max-md:size-11"
                      aria-label={m.removeOf(name)}
                      title={m.removeOf(name)}
                      onClick={() => {
                        remove.reset();
                        setConfirmingId(member.userId);
                      }}
                    >
                      <XIcon />
                    </Button>
                  ) : null}
                </span>
              )}
            </li>
          );
        })}
      </ul>
    </div>
  );
}
