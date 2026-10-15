"use client";

import { CheckIcon, SendIcon } from "lucide-react";
import { TimeAgo } from "@/components/dates";
import { Button } from "@/components/ui/button";
import { FormAlert } from "@/features/auth/components/form-alert";
import { apiErrorMessage } from "@/lib/api/error-message";
import { useRefreshingMutation } from "@/lib/use-refreshing-mutation";
import { messages } from "@/messages";
import { requestProjectAccess } from "../api";

const m = messages.projects;

/**
 * On the "권한 없음" screen: asks the project's managers to let the person in (D-128). Once asked,
 * it says so, with when, until a manager answers.
 */
export function RequestAccessButton({ projectId, requestedAt }: { projectId: string; requestedAt: string | null }) {
  const request = useRefreshingMutation(requestProjectAccess);

  if (requestedAt) {
    return (
      <div role="status" className="flex w-full flex-col items-center gap-1 rounded-lg border bg-muted/50 px-4 py-3 text-[0.8125rem] leading-5">
        <span className="flex items-center gap-1.5 font-medium">
          <CheckIcon className="size-4 text-primary" aria-hidden="true" />
          {m.accessRequested}
          <span className="font-normal text-muted-foreground">
            · <TimeAgo iso={requestedAt} />
          </span>
        </span>
        <span className="text-muted-foreground">{m.accessRequestedHint}</span>
      </div>
    );
  }

  return (
    <div className="flex w-full flex-col items-center gap-2">
      {request.isError ? <FormAlert>{apiErrorMessage(request.error)}</FormAlert> : null}
      <Button className="max-md:h-11" disabled={request.isPending} onClick={() => request.mutate(projectId)}>
        <SendIcon data-icon="inline-start" />
        {request.isPending ? m.requestingAccess : m.requestAccess}
      </Button>
    </div>
  );
}
