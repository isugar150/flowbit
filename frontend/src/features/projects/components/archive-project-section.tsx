"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { ArchiveIcon } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Spinner } from "@/components/ui/spinner";
import { FormAlert } from "@/features/auth/components/form-alert";
import { apiErrorMessage } from "@/lib/api/error-message";
import { useRefreshingMutation } from "@/lib/use-refreshing-mutation";
import { messages } from "@/messages";
import { archiveProject } from "../api";

const m = messages.projects;

/**
 * "아카이브" at the bottom of a project's settings, for those who manage it. Asks once more in
 * place; then the project is put away and the person lands on the list of archived projects,
 * where it can be restored.
 */
export function ArchiveProjectSection({ projectId }: { projectId: string }) {
  const router = useRouter();
  const [confirming, setConfirming] = useState(false);
  const mutation = useRefreshingMutation(archiveProject, () => router.replace("/archived"));

  return (
    <div className="flex flex-col gap-3">
      {mutation.isError ? <FormAlert>{apiErrorMessage(mutation.error)}</FormAlert> : null}
      <div className="flex flex-wrap items-center gap-2">
        {confirming ? (
          <>
            <Button
              type="button"
              variant="destructive"
              className="h-9 max-md:h-11"
              disabled={mutation.isPending}
              onClick={() => mutation.mutate(projectId)}
            >
              {mutation.isPending ? <Spinner data-icon="inline-start" aria-hidden="true" /> : <ArchiveIcon data-icon="inline-start" />}
              {mutation.isPending ? m.archiving : m.archiveConfirm}
            </Button>
            <Button
              type="button"
              variant="ghost"
              className="h-9 max-md:h-11"
              disabled={mutation.isPending}
              onClick={() => setConfirming(false)}
            >
              {m.cancel}
            </Button>
          </>
        ) : (
          <Button type="button" variant="outline" className="h-9 max-md:h-11" onClick={() => setConfirming(true)}>
            <ArchiveIcon data-icon="inline-start" />
            {m.archive}
          </Button>
        )}
      </div>
    </div>
  );
}
