"use client";

import { useState } from "react";
import { RotateCcwIcon, Trash2Icon } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Spinner } from "@/components/ui/spinner";
import { apiErrorMessage } from "@/lib/api/error-message";
import { useRefreshingMutation } from "@/lib/use-refreshing-mutation";
import { messages } from "@/messages";
import { deleteIssue, restoreIssue } from "../api";
import type { IssueDetail } from "../types";

const m = messages.issues.deleted;

/** Shown on top of a deleted issue: what state it is in, and "복원" for those who may. */
export function DeletedIssueBanner({ issue }: { issue: IssueDetail }) {
  const restore = useRefreshingMutation(restoreIssue);
  if (!issue.deletedAt) return null;

  return (
    <div role="status" className="flex flex-wrap items-center gap-2 rounded-lg border bg-muted px-3 py-2 text-[0.8125rem] leading-5">
      <Trash2Icon className="size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
      <p className="min-w-0 flex-1">{restore.isError ? apiErrorMessage(restore.error) : m.banner}</p>
      {issue.canRestore ? (
        <Button
          type="button"
          variant="outline"
          size="sm"
          className="max-md:h-11"
          disabled={restore.isPending}
          onClick={() => restore.mutate(issue.number)}
        >
          {restore.isPending ? <Spinner data-icon="inline-start" aria-hidden="true" /> : <RotateCcwIcon data-icon="inline-start" />}
          {m.restore}
        </Button>
      ) : null}
    </div>
  );
}

/**
 * "이슈 삭제" at the bottom of the issue's side panel. Asks for a second press. The issue stays
 * open afterwards, now read-only with the banner above: deleting hides it from the lists, it does
 * not destroy it.
 */
export function DeleteIssueButton({ issue }: { issue: IssueDetail }) {
  const [confirming, setConfirming] = useState(false);
  const remove = useRefreshingMutation(deleteIssue, () => setConfirming(false));
  if (!issue.canEdit) return null;

  return (
    <div className="flex flex-col gap-1">
      <Button
        type="button"
        variant="ghost"
        size="sm"
        className="-ml-2 w-fit text-muted-foreground hover:text-destructive max-md:h-11"
        disabled={remove.isPending}
        onBlur={() => setConfirming(false)}
        onClick={() => (confirming ? remove.mutate(issue.number) : setConfirming(true))}
      >
        <Trash2Icon data-icon="inline-start" />
        {confirming ? m.deleteConfirm : m.delete}
      </Button>
      <p className="text-xs leading-4 text-muted-foreground">{remove.isError ? apiErrorMessage(remove.error) : m.deleteHint}</p>
    </div>
  );
}
