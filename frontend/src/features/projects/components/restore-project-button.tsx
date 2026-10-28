"use client";

import { ArchiveRestoreIcon } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Spinner } from "@/components/ui/spinner";
import { apiErrorMessage } from "@/lib/api/error-message";
import { useRefreshingMutation } from "@/lib/use-refreshing-mutation";
import { messages } from "@/messages";
import { restoreProject } from "../api";
import type { ProjectApiScope } from "../scope";

const m = messages.projects;

/**
 * "복원" on a line of the archived projects: the project goes back to the lists as it was. `scope` "admin" is the
 * administrator's list in 관리자 설정 > 프로젝트 (D-236), which restores any project, not only those the person manages.
 */
export function RestoreProjectButton({ projectId, name, scope }: { projectId: string; name: string; scope?: ProjectApiScope }) {
  const mutation = useRefreshingMutation((id: string) => restoreProject(id, scope));

  return (
    <Button
      type="button"
      variant="outline"
      size="sm"
      className="text-foreground max-md:h-11"
      aria-label={m.restoreOf(name)}
      title={mutation.isError ? apiErrorMessage(mutation.error) : undefined}
      disabled={mutation.isPending}
      onClick={() => mutation.mutate(projectId)}
    >
      {mutation.isPending ? <Spinner data-icon="inline-start" aria-hidden="true" /> : <ArchiveRestoreIcon data-icon="inline-start" />}
      {m.restore}
    </Button>
  );
}
