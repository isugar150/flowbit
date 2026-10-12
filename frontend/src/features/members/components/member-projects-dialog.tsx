"use client";

import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog";
import {
  NativeSelect,
  NativeSelectOption,
} from "@/components/ui/native-select";
import { Spinner } from "@/components/ui/spinner";
import { FormAlert } from "@/features/auth/components/form-alert";
import { putProjectMember, removeProjectMember } from "@/features/projects/api";
import type { ProjectRole } from "@/features/projects/types";
import { apiErrorMessage } from "@/lib/api/error-message";
import { messages } from "@/messages";
import { memberProjectRoles } from "../api";

const m = messages.members;
const NONE = "NONE";
const ROLES: ProjectRole[] = ["VIEWER", "EDITOR", "MANAGER"];

/**
 * What one member may do in each project, set in one place. Someone who has just signed in for
 * the first time through LDAP or single sign-on belongs to no project; this is where they get in.
 */
export function MemberProjectsDialog({
  memberId,
  memberName,
}: {
  memberId: string;
  memberName: string;
}) {
  const [open, setOpen] = useState(false);
  const queryClient = useQueryClient();
  const queryKey = ["member-projects", memberId];
  // Read when the dialog opens, and again each time: project roles also change elsewhere.
  const roles = useQuery({
    queryKey,
    queryFn: () => memberProjectRoles(memberId),
    enabled: open,
    staleTime: 0,
  });
  const change = useMutation({
    mutationFn: async ({
      projectId,
      role,
    }: {
      projectId: string;
      role: ProjectRole | null;
    }): Promise<void> => {
      if (role)
        await putProjectMember({ id: projectId, userId: memberId, role });
      else await removeProjectMember({ id: projectId, userId: memberId });
    },
    onSettled: () => queryClient.invalidateQueries({ queryKey }),
  });

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        setOpen(next);
        if (next) change.reset();
      }}
    >
      <DialogTrigger
        render={<Button variant="ghost" size="sm" className="max-md:h-11" />}
      >
        {m.projectRoles}
      </DialogTrigger>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{m.projectRolesTitle(memberName)}</DialogTitle>
          <DialogDescription>{m.projectRolesDescription}</DialogDescription>
        </DialogHeader>
        {change.isError ? (
          <FormAlert>{apiErrorMessage(change.error)}</FormAlert>
        ) : null}
        {roles.isError ? (
          <FormAlert>{apiErrorMessage(roles.error)}</FormAlert>
        ) : null}
        {roles.isPending ? (
          <p className="flex items-center gap-2 text-muted-foreground">
            <Spinner aria-hidden="true" />
            {m.projectRolesLoading}
          </p>
        ) : null}
        {roles.data?.length === 0 ? (
          <p className="text-muted-foreground">{m.projectRolesEmpty}</p>
        ) : null}
        {roles.data && roles.data.length > 0 ? (
          <ul className="flex max-h-[50vh] flex-col divide-y overflow-y-auto rounded-lg border">
            {roles.data.map((project) => (
              <li
                key={project.projectId}
                className="flex items-center justify-between gap-3 px-3 py-2"
              >
                <span className="min-w-0 truncate">{project.name}</span>
                <NativeSelect
                  size="sm"
                  className="w-28 shrink-0 max-md:h-11"
                  aria-label={m.projectRoleIn(project.name)}
                  value={project.role ?? NONE}
                  disabled={change.isPending}
                  onChange={(event) =>
                    change.mutate({
                      projectId: project.projectId,
                      role:
                        event.target.value === NONE
                          ? null
                          : (event.target.value as ProjectRole),
                    })
                  }
                >
                  <NativeSelectOption value={NONE}>
                    {m.projectRoleNone}
                  </NativeSelectOption>
                  {ROLES.map((role) => (
                    <NativeSelectOption key={role} value={role}>
                      {messages.roles[role]}
                    </NativeSelectOption>
                  ))}
                </NativeSelect>
              </li>
            ))}
          </ul>
        ) : null}
        <DialogFooter>
          <Button
            type="button"
            variant="outline"
            className="max-md:h-11"
            onClick={() => setOpen(false)}
          >
            {m.close}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
