"use client";

import { useState } from "react";
import { XIcon } from "lucide-react";
import { Person, personName } from "@/components/person";
import { Button } from "@/components/ui/button";
import { NativeSelect, NativeSelectOption } from "@/components/ui/native-select";
import { FormAlert } from "@/features/auth/components/form-alert";
import { apiErrorMessage } from "@/lib/api/error-message";
import { useOptimisticMutation, useRefreshingMutation } from "@/lib/use-refreshing-mutation";
import { messages } from "@/messages";
import { putProjectMember, removeProjectMember } from "../api";
import type { ProjectMember, ProjectRole } from "../types";

const m = messages.projects;
const ROLES: ProjectRole[] = ["MANAGER", "EDITOR", "VIEWER"];

/**
 * The people on the members card of a project overview. Those who manage the project change a
 * person's role and take someone out right here: the role is a list that saves when picked, and
 * the "x" asks once more in place before it removes.
 */
export function ProjectMemberList({
  projectId,
  members: saved,
  canManage,
}: {
  projectId: string;
  members: ProjectMember[];
  canManage: boolean;
}) {
  const [confirmingId, setConfirmingId] = useState<string | null>(null);
  const remove = useRefreshingMutation(removeProjectMember, () => setConfirmingId(null));
  // A picked role shows right away; a list driven by the saved role alone would snap back to the
  // old one until the page is read again.
  const change = useOptimisticMutation(
    saved,
    (members, next: { id: string; userId: string; role: ProjectRole }) =>
      members.map((member) => (member.userId === next.userId ? { ...member, role: next.role } : member)),
    putProjectMember,
  );
  const members = change.value;
  const failure = remove.error ?? change.error;

  return (
    <div className="flex flex-col gap-2 px-1 py-1">
      {failure ? <FormAlert>{apiErrorMessage(failure)}</FormAlert> : null}
      <ul className="flex flex-col gap-2">
        {members.map((member) => {
          const name = personName(member);
          const confirming = confirmingId === member.userId;
          return (
            <li key={member.userId} className="flex min-h-8 items-center justify-between gap-2">
              <Person person={member} />
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
                  {canManage ? (
                    <NativeSelect
                      size="sm"
                      className="w-20 max-md:h-11"
                      aria-label={messages.members.roleOf(name)}
                      value={member.role}
                      disabled={remove.isPending}
                      onChange={(event) => {
                        remove.reset();
                        change.mutate({ id: projectId, userId: member.userId, role: event.target.value as ProjectRole });
                      }}
                    >
                      {ROLES.map((role) => (
                        <NativeSelectOption key={role} value={role}>
                          {messages.roles[role]}
                        </NativeSelectOption>
                      ))}
                    </NativeSelect>
                  ) : (
                    <span className="text-[0.8125rem] leading-5 text-muted-foreground">{messages.roles[member.role]}</span>
                  )}
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
                        change.reset();
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
