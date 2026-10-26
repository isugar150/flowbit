"use client";

import { CopyPlusIcon } from "lucide-react";
import { useRouter } from "next/navigation";
import { Button } from "@/components/ui/button";
import { Spinner } from "@/components/ui/spinner";
import { apiErrorMessage } from "@/lib/api/error-message";
import { useRefreshingMutation } from "@/lib/use-refreshing-mutation";
import { messages } from "@/messages";
import { duplicateIssue } from "../api";
import type { IssueDetail } from "../types";

const m = messages.issues.duplicate;

/**
 * "티켓 복제" above "티켓 삭제" in the issue's side panel (D-212). One press makes the copy, without
 * its sub-issues, and opens it. Whoever may change the issue may copy it.
 */
export function DuplicateIssueButton({ issue }: { issue: IssueDetail }) {
  const router = useRouter();
  const duplicate = useRefreshingMutation(duplicateIssue, (copy) => router.push(`/issue/${copy.key}`));
  if (!issue.canEdit) return null;

  return (
    <div className="flex flex-col gap-1">
      <Button
        type="button"
        variant="ghost"
        size="sm"
        className="-ml-2 w-fit text-muted-foreground hover:text-foreground max-md:h-11"
        disabled={duplicate.isPending}
        onClick={() => duplicate.mutate(issue.number)}
      >
        {duplicate.isPending ? <Spinner data-icon="inline-start" aria-hidden="true" /> : <CopyPlusIcon data-icon="inline-start" />}
        {m.button}
      </Button>
      {duplicate.isError ? <p className="text-xs leading-4 text-destructive">{apiErrorMessage(duplicate.error)}</p> : null}
    </div>
  );
}
