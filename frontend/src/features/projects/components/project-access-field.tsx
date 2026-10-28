"use client";

import { FormAlert } from "@/features/auth/components/form-alert";
import { apiErrorMessage } from "@/lib/api/error-message";
import { useOptimisticMutation } from "@/lib/use-refreshing-mutation";
import { useProjectApi } from "../project-api-scope";
import { OpenToAllField } from "./open-to-all-field";

/**
 * Who can enter the project, in its settings (managers only). The pick is saved at once, and a
 * pick the server refuses goes back by itself.
 */
export function ProjectAccessField({ projectId, openToAll }: { projectId: string; openToAll: boolean }) {
  const { updateProject } = useProjectApi();
  const change = useOptimisticMutation(
    openToAll,
    (_current, next: { id: string; openToAll: boolean }) => next.openToAll,
    (next) => updateProject({ id: next.id, changes: { openToAll: next.openToAll } }),
  );

  return (
    <div className="flex flex-col gap-3">
      {change.isError ? <FormAlert>{apiErrorMessage(change.error)}</FormAlert> : null}
      <OpenToAllField
        idPrefix="project-access"
        value={change.value}
        onChange={(open) => {
          change.reset();
          change.mutate({ id: projectId, openToAll: open });
        }}
      />
    </div>
  );
}
