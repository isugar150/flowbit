"use client";

import { useState } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Field, FieldLabel } from "@/components/ui/field";
import { NativeSelect, NativeSelectOption } from "@/components/ui/native-select";
import { Person, personName } from "@/components/person";
import { FormAlert } from "@/features/auth/components/form-alert";
import type { UserSummary } from "@/features/members/types";
import { apiErrorMessage } from "@/lib/api/error-message";
import { useOptimisticMutation, useRefreshingMutation } from "@/lib/use-refreshing-mutation";
import { messages } from "@/messages";
import { useProjectApi } from "../project-api-scope";
import type { ProjectMember, ProjectRole } from "../types";

const m = messages.projects;
const ROLES: ProjectRole[] = ["MANAGER", "EDITOR", "VIEWER"];

function RoleOptions() {
  return ROLES.map((role) => (
    <NativeSelectOption key={role} value={role}>
      {messages.roles[role]}
    </NativeSelectOption>
  ));
}

/** Who can enter the project and what they may do. Only managers get the controls. */
export function ProjectMembersPanel({
  projectId,
  members: saved,
  openToAll,
  users,
  canManage,
  currentUserId,
}: {
  projectId: string;
  /** The people who were added, not everyone a project open to all lets in. */
  members: ProjectMember[];
  /** The project is open to every member of the workspace (D-192): the list is not who can enter. */
  openToAll: boolean;
  /** Everyone who could be added; empty when the viewer cannot manage. */
  users: UserSummary[];
  canManage: boolean;
  currentUserId: string;
}) {
  const { putProjectMember, removeProjectMember } = useProjectApi();
  const [newUserId, setNewUserId] = useState("");
  const [newRole, setNewRole] = useState<ProjectRole>("EDITOR");
  const put = useRefreshingMutation(putProjectMember, () => setNewUserId(""));
  const remove = useRefreshingMutation(removeProjectMember);
  // A picked role shows right away; a list driven by the saved role alone would snap back to the
  // old one until the page is read again.
  const change = useOptimisticMutation(
    saved,
    (members, next: { id: string; userId: string; role: ProjectRole }) =>
      members.map((member) => (member.userId === next.userId ? { ...member, role: next.role } : member)),
    putProjectMember,
  );
  const members = change.value;
  const busy = put.isPending || remove.isPending;
  const failure = put.error ?? remove.error ?? change.error;
  const memberIds = new Set(members.map((member) => member.userId));
  const candidates = users.filter((user) => !memberIds.has(user.id));

  const run = (action: () => void) => {
    put.reset();
    remove.reset();
    change.reset();
    action();
  };

  return (
    <div className="flex flex-col gap-4">
      {failure ? <FormAlert>{apiErrorMessage(failure)}</FormAlert> : null}
      {openToAll ? <p className="text-muted-foreground">{m.openMembersNote}</p> : null}
      <ul className="flex flex-col divide-y rounded-xl border">
        {members.map((member) => {
          const name = personName(member);
          return (
            <li key={member.userId} className="flex flex-wrap items-center justify-between gap-x-4 gap-y-2 px-4 py-3">
              <Person person={member}>
                {member.userId === currentUserId ? <Badge variant="secondary">{messages.members.you}</Badge> : null}
              </Person>
              {canManage ? (
                <div className="flex items-center gap-1">
                  <NativeSelect
                    size="sm"
                    className="w-20 max-md:h-11"
                    aria-label={messages.members.roleOf(name)}
                    value={member.role}
                    disabled={remove.isPending}
                    onChange={(event) =>
                      run(() =>
                        change.mutate({ id: projectId, userId: member.userId, role: event.target.value as ProjectRole }),
                      )
                    }
                  >
                    <RoleOptions />
                  </NativeSelect>
                  <Button
                    variant="ghost"
                    size="sm"
                    className="max-md:h-11"
                    aria-label={m.removeOf(name)}
                    disabled={busy}
                    onClick={() => run(() => remove.mutate({ id: projectId, userId: member.userId }))}
                  >
                    {m.remove}
                  </Button>
                </div>
              ) : (
                <span className="text-muted-foreground">{messages.roles[member.role]}</span>
              )}
            </li>
          );
        })}
      </ul>
      {canManage ? (
        // Always there, also when everyone is in the project already: then it only says so (the user's call, 2026-10-21).
        <form
          className="flex flex-wrap items-end gap-2"
          onSubmit={(event) => {
            event.preventDefault();
            if (newUserId) run(() => put.mutate({ id: projectId, userId: newUserId, role: newRole }));
          }}
        >
          <Field className="min-w-48 flex-1 sm:max-w-72">
            <FieldLabel htmlFor="add-member-user">{m.addMemberLabel}</FieldLabel>
            <NativeSelect
              id="add-member-user"
              className="max-md:h-11"
              value={newUserId}
              disabled={candidates.length === 0}
              onChange={(event) => setNewUserId(event.target.value)}
            >
              <NativeSelectOption value="">{candidates.length > 0 ? m.addMemberPlaceholder : m.noOneToAdd}</NativeSelectOption>
              {candidates.map((user) => (
                <NativeSelectOption key={user.id} value={user.id}>
                  {user.displayName ? `${user.displayName} (${user.loginId})` : user.loginId}
                </NativeSelectOption>
              ))}
            </NativeSelect>
          </Field>
          <Field className="w-24">
            <FieldLabel htmlFor="add-member-role">{m.addMemberRoleLabel}</FieldLabel>
            <NativeSelect
              id="add-member-role"
              className="max-md:h-11"
              value={newRole}
              disabled={candidates.length === 0}
              onChange={(event) => setNewRole(event.target.value as ProjectRole)}
            >
              <RoleOptions />
            </NativeSelect>
          </Field>
          <Button type="submit" variant="outline" className="max-md:h-11" disabled={busy || !newUserId}>
            {m.addMember}
          </Button>
        </form>
      ) : null}
    </div>
  );
}
