"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  NativeSelect,
  NativeSelectOption,
} from "@/components/ui/native-select";
import { Skeleton } from "@/components/ui/skeleton";
import { FormAlert } from "@/features/auth/components/form-alert";
import { putProjectMember, removeProjectMember } from "@/features/projects/api";
import type { ProjectRole } from "@/features/projects/types";
import { apiErrorMessage } from "@/lib/api/error-message";
import { messages } from "@/messages";
import { memberProjectRoles, type MemberProjectRole } from "../api";

const m = messages.members;
const NONE = "NONE";
const ROLES: ProjectRole[] = ["VIEWER", "EDITOR", "MANAGER"];

/**
 * What one member may do in each project, set in one place. Someone who has just signed in for
 * the first time through LDAP or single sign-on belongs to no project; this is where they get in.
 */
export function MemberProjectRoles({ memberId }: { memberId: string }) {
  const queryClient = useQueryClient();
  const queryKey = ["member-projects", memberId];
  // Read again each time a member is opened: project roles also change elsewhere.
  const roles = useQuery({
    queryKey,
    queryFn: () => memberProjectRoles(memberId),
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
    // The picked role shows right away; a refused one goes back to what it was.
    onMutate: async ({ projectId, role }) => {
      await queryClient.cancelQueries({ queryKey });
      const before = queryClient.getQueryData<MemberProjectRole[]>(queryKey);
      queryClient.setQueryData<MemberProjectRole[]>(queryKey, (current) =>
        current?.map((project) => (project.projectId === projectId ? { ...project, role } : project)),
      );
      return { before };
    },
    onError: (_error, _change, context) => {
      if (context?.before) queryClient.setQueryData(queryKey, context.before);
    },
    onSettled: () => queryClient.invalidateQueries({ queryKey }),
  });

  if (roles.isPending) {
    return (
      <div className="flex flex-col gap-2" aria-busy="true">
        <span className="sr-only">{m.projectRolesLoading}</span>
        <Skeleton className="h-8" />
        <Skeleton className="h-8" />
      </div>
    );
  }
  if (roles.isError) return <FormAlert>{apiErrorMessage(roles.error)}</FormAlert>;
  if (roles.data.length === 0) {
    return (
      <p className="text-[0.8125rem] leading-5 text-muted-foreground">
        {m.projectRolesEmpty}
      </p>
    );
  }

  return (
    <div className="flex flex-col gap-3">
      {change.isError ? (
        <FormAlert>{apiErrorMessage(change.error)}</FormAlert>
      ) : null}
      <ul className="flex max-h-72 flex-col divide-y overflow-y-auto rounded-lg border">
        {roles.data.map((project) => (
          <li
            key={project.projectId}
            className="flex items-center justify-between gap-3 py-1.5 pr-1.5 pl-3"
          >
            <span className="min-w-0 truncate text-[0.8125rem] leading-5">
              {project.name}
            </span>
            <NativeSelect
              size="sm"
              className="w-28 shrink-0 max-md:h-11"
              aria-label={m.projectRoleIn(project.name)}
              value={project.role ?? NONE}
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
    </div>
  );
}
