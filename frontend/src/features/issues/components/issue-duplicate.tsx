"use client";

import { useState } from "react";
import { CopyPlusIcon } from "lucide-react";
import { useRouter } from "next/navigation";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Spinner } from "@/components/ui/spinner";
import { apiErrorMessage } from "@/lib/api/error-message";
import { useRefreshingMutation } from "@/lib/use-refreshing-mutation";
import { messages } from "@/messages";
import { duplicateIssue } from "../api";
import type { IssueDetail } from "../types";

const m = messages.issues.duplicate;

/**
 * "티켓 복제" above "티켓 삭제" in the issue's side panel (D-212). Asks first (D-213); "복제" then makes
 * the copy, without its sub-issues, and opens it. Whoever may change the issue may copy it.
 */
export function DuplicateIssueButton({ issue }: { issue: IssueDetail }) {
  const router = useRouter();
  const [open, setOpen] = useState(false);
  const duplicate = useRefreshingMutation(duplicateIssue, (copy) => {
    setOpen(false);
    router.push(`/issue/${copy.key}`);
  });
  if (!issue.canEdit) return null;

  return (
    <>
      <Button
        type="button"
        variant="ghost"
        size="sm"
        className="-ml-2 w-fit text-muted-foreground hover:text-foreground max-md:h-11"
        onClick={() => {
          duplicate.reset();
          setOpen(true);
        }}
      >
        <CopyPlusIcon data-icon="inline-start" />
        {m.button}
      </Button>
      <Dialog
        open={open}
        onOpenChange={(next) => {
          // Not while the copy is being made: closing then would hide the result, not stop it.
          if (!duplicate.isPending) setOpen(next);
        }}
      >
        <DialogContent showCloseButton={false} className="sm:max-w-sm" role="alertdialog">
          <DialogHeader>
            <DialogTitle>{m.title}</DialogTitle>
            <DialogDescription>{m.description(issue.key)}</DialogDescription>
          </DialogHeader>
          {duplicate.isError ? <p className="text-xs leading-4 text-destructive">{apiErrorMessage(duplicate.error)}</p> : null}
          <DialogFooter>
            <Button type="button" variant="outline" className="max-md:h-11" disabled={duplicate.isPending} onClick={() => setOpen(false)}>
              {m.cancel}
            </Button>
            <Button type="button" className="max-md:h-11" disabled={duplicate.isPending} onClick={() => duplicate.mutate(issue.number)}>
              {duplicate.isPending ? <Spinner data-icon="inline-start" aria-hidden="true" /> : null}
              {m.confirm}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  );
}
