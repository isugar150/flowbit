"use client";

import { BellIcon, BellOffIcon } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Spinner } from "@/components/ui/spinner";
import { apiErrorMessage } from "@/lib/api/error-message";
import { useRefreshingMutation } from "@/lib/use-refreshing-mutation";
import { messages } from "@/messages";
import { setProjectSubscribed } from "../api";

const m = messages.projects;

/**
 * Follow or stop following a project. Whoever follows it is told about every change to its issues
 * through the notification channels they set up, without following each issue.
 */
export function ProjectSubscribeButton({ projectId, subscribed }: { projectId: string; subscribed: boolean }) {
  const mutation = useRefreshingMutation(setProjectSubscribed);

  return (
    <Button
      type="button"
      variant="ghost"
      size="sm"
      className="text-muted-foreground max-md:h-11"
      title={mutation.isError ? apiErrorMessage(mutation.error) : m.subscribeHint}
      disabled={mutation.isPending}
      onClick={() => mutation.mutate({ id: projectId, subscribed: !subscribed })}
    >
      {mutation.isPending ? (
        <Spinner data-icon="inline-start" aria-hidden="true" />
      ) : subscribed ? (
        <BellOffIcon data-icon="inline-start" />
      ) : (
        <BellIcon data-icon="inline-start" />
      )}
      {subscribed ? m.unsubscribe : m.subscribe}
    </Button>
  );
}
