"use client";

import { useState } from "react";
import { TimeAgo } from "@/components/dates";
import { Person, personName } from "@/components/person";
import { Button } from "@/components/ui/button";
import { NativeSelect, NativeSelectOption } from "@/components/ui/native-select";
import { FormAlert } from "@/features/auth/components/form-alert";
import { apiErrorMessage } from "@/lib/api/error-message";
import { useRefreshingMutation } from "@/lib/use-refreshing-mutation";
import { messages } from "@/messages";
import { useProjectApi } from "../project-api-scope";
import type { ProjectAccessRequest, ProjectRole } from "../types";

const m = messages.projects;
const ROLES: ProjectRole[] = ["MANAGER", "EDITOR", "VIEWER"];

/**
 * People who asked to be let into the project, below its members in the settings (D-128).
 * Managers pick a role for each and approve, or turn the request down. Notifications about a
 * request link here (`#access-requests`). Shows nothing when nobody is waiting.
 */
export function ProjectAccessRequests({ projectId, requests }: { projectId: string; requests: ProjectAccessRequest[] }) {
  const { approveProjectAccess, rejectProjectAccess } = useProjectApi();
  // The role picked for each person before approving; "편집" until something else is picked.
  const [roles, setRoles] = useState<Record<string, ProjectRole>>({});
  const approve = useRefreshingMutation(approveProjectAccess);
  const reject = useRefreshingMutation(rejectProjectAccess);
  const busy = approve.isPending || reject.isPending;
  const failure = approve.error ?? reject.error;

  if (requests.length === 0) return null;

  return (
    <section id="access-requests" aria-labelledby="access-requests-title" className="flex scroll-mt-20 flex-col gap-2">
      <h3 id="access-requests-title" className="flex items-center gap-2 text-[0.8125rem] leading-5 font-medium">
        {m.accessRequestsTitle}
        <span className="font-normal text-muted-foreground tabular-nums">{requests.length}</span>
      </h3>
      {failure ? <FormAlert>{apiErrorMessage(failure)}</FormAlert> : null}
      <ul className="flex flex-col divide-y rounded-xl border border-dashed">
        {requests.map((request) => {
          const name = personName(request);
          const role = roles[request.userId] ?? "EDITOR";
          return (
            <li key={request.userId} className="flex flex-wrap items-center justify-between gap-x-4 gap-y-2 px-4 py-3">
              <Person person={request}>
                <span className="text-xs leading-4 font-normal text-muted-foreground">
                  {m.accessRequestedAt} <TimeAgo iso={request.requestedAt} />
                </span>
              </Person>
              <div className="flex items-center gap-1">
                <NativeSelect
                  size="sm"
                  className="w-20 max-md:h-11"
                  aria-label={m.approveRoleOf(name)}
                  value={role}
                  disabled={busy}
                  onChange={(event) => setRoles((current) => ({ ...current, [request.userId]: event.target.value as ProjectRole }))}
                >
                  {ROLES.map((value) => (
                    <NativeSelectOption key={value} value={value}>
                      {messages.roles[value]}
                    </NativeSelectOption>
                  ))}
                </NativeSelect>
                <Button
                  size="sm"
                  className="max-md:h-11"
                  aria-label={m.approveOf(name)}
                  disabled={busy}
                  onClick={() => {
                    reject.reset();
                    approve.mutate({ id: projectId, userId: request.userId, role });
                  }}
                >
                  {m.approve}
                </Button>
                <Button
                  variant="ghost"
                  size="sm"
                  className="max-md:h-11"
                  aria-label={m.rejectOf(name)}
                  disabled={busy}
                  onClick={() => {
                    approve.reset();
                    reject.mutate({ id: projectId, userId: request.userId });
                  }}
                >
                  {m.reject}
                </Button>
              </div>
            </li>
          );
        })}
      </ul>
    </section>
  );
}
