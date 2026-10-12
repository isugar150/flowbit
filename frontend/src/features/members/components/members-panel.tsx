"use client";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  NativeSelect,
  NativeSelectOption,
} from "@/components/ui/native-select";
import { Person, personName } from "@/components/person";
import { FormAlert } from "@/features/auth/components/form-alert";
import { apiErrorMessage } from "@/lib/api/error-message";
import { useRefreshingMutation } from "@/lib/use-refreshing-mutation";
import { messages } from "@/messages";
import { updateMember } from "../api";
import type { Member, WorkspaceRole } from "../types";
import { CreateMemberDialog } from "./create-member-dialog";
import { MemberProjectsDialog } from "./member-projects-dialog";
import { ResetPasswordDialog } from "./reset-password-dialog";

const m = messages.members;

/** Everyone who can sign in, with the controls administrators have over them. */
export function MembersPanel({
  members,
  currentUserId,
  ldapEnabled,
}: {
  members: Member[];
  currentUserId: string;
  /** With LDAP on, people arrive by signing in and their passwords live in the directory. */
  ldapEnabled: boolean;
}) {
  const mutation = useRefreshingMutation(updateMember);

  return (
    <div className="flex flex-col gap-4">
      {ldapEnabled ? (
        <p className="rounded-lg border bg-muted/40 px-3 py-2 text-[0.8125rem] leading-5 text-muted-foreground">
          {m.ldapNotice}
        </p>
      ) : (
        <div className="flex justify-end">
          <CreateMemberDialog />
        </div>
      )}
      {mutation.isError ? (
        <FormAlert>{apiErrorMessage(mutation.error)}</FormAlert>
      ) : null}
      <ul className="flex flex-col divide-y rounded-xl border">
        {members.map((member) => {
          const isSelf = member.id === currentUserId;
          const disabled = member.status === "DISABLED";
          const name = personName(member);
          return (
            <li
              key={member.id}
              className="flex flex-wrap items-center justify-between gap-x-4 gap-y-2 px-4 py-3"
            >
              <div className={disabled ? "min-w-0 opacity-60" : "min-w-0"}>
                <Person person={member}>
                  {isSelf ? <Badge variant="secondary">{m.you}</Badge> : null}
                  {member.authSource !== "LOCAL" ? (
                    <Badge variant="outline">
                      {m.authSources[member.authSource]}
                    </Badge>
                  ) : null}
                  {disabled ? (
                    <Badge variant="outline">{m.disabled}</Badge>
                  ) : null}
                </Person>
              </div>
              <div className="flex flex-wrap items-center gap-1">
                <NativeSelect
                  size="sm"
                  className="w-24 max-md:h-11"
                  aria-label={m.roleOf(name)}
                  value={member.role}
                  // Administrators cannot change themselves, so one always remains.
                  disabled={isSelf || mutation.isPending}
                  onChange={(event) =>
                    mutation.mutate({
                      id: member.id,
                      role: event.target.value as WorkspaceRole,
                    })
                  }
                >
                  <NativeSelectOption value="MEMBER">
                    {messages.roles.MEMBER}
                  </NativeSelectOption>
                  <NativeSelectOption value="ADMIN">
                    {messages.roles.ADMIN}
                  </NativeSelectOption>
                </NativeSelect>
                <MemberProjectsDialog memberId={member.id} memberName={name} />
                {/* With LDAP on only administrators keep a password here; otherwise only local accounts have one. */}
                {(
                  ldapEnabled
                    ? member.role === "ADMIN"
                    : member.authSource === "LOCAL"
                ) ? (
                  <ResetPasswordDialog memberId={member.id} memberName={name} />
                ) : null}
                {isSelf ? null : (
                  <Button
                    variant="ghost"
                    size="sm"
                    className="max-md:h-11"
                    disabled={mutation.isPending}
                    onClick={() =>
                      mutation.mutate({
                        id: member.id,
                        status: disabled ? "ACTIVE" : "DISABLED",
                      })
                    }
                  >
                    {disabled ? m.enable : m.disable}
                  </Button>
                )}
              </div>
            </li>
          );
        })}
      </ul>
    </div>
  );
}
