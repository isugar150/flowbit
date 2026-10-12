"use client";

import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Spinner } from "@/components/ui/spinner";
import { Textarea } from "@/components/ui/textarea";
import { FormAlert } from "@/features/auth/components/form-alert";
import { ChipSelect } from "@/features/issues/components/chip-select";
import { PersonAvatar } from "@/features/issues/options";
import { apiErrorMessage } from "@/lib/api/error-message";
import { useRefreshingMutation } from "@/lib/use-refreshing-mutation";
import { messages } from "@/messages";
import { postProjectUpdate } from "../api";
import { HealthBadge, healthOptions, timeAgo } from "../meta";
import type { ProjectHealth, ProjectUpdate } from "../types";

const m = messages.projects;
const BODY_MAX_LENGTH = 5000;

/**
 * Project updates on the overview: people who may edit post how the project is going, with a
 * health; the latest health is what the project list shows. Newest first.
 */
export function ProjectUpdates({
  projectKey,
  updates,
  canEdit,
}: {
  projectKey: string;
  updates: ProjectUpdate[];
  canEdit: boolean;
}) {
  const [health, setHealth] = useState<ProjectHealth>(updates[0]?.health ?? "ON_TRACK");
  const [body, setBody] = useState("");
  const mutation = useRefreshingMutation(postProjectUpdate, () => setBody(""));

  return (
    <section className="flex flex-col gap-3" aria-labelledby="project-updates-title">
      <h3 id="project-updates-title" className="text-[0.8125rem] leading-5 font-medium text-muted-foreground">
        {m.updatesTitle}
      </h3>
      {canEdit ? (
        <form
          className="flex flex-col gap-2 rounded-lg border p-3 has-[textarea:focus-visible]:border-ring has-[textarea:focus-visible]:ring-3 has-[textarea:focus-visible]:ring-ring/50"
          onSubmit={(event) => {
            event.preventDefault();
            if (!mutation.isPending) mutation.mutate({ key: projectKey, health, body });
          }}
        >
          {mutation.isError ? <FormAlert>{apiErrorMessage(mutation.error)}</FormAlert> : null}
          <Textarea
            value={body}
            onChange={(event) => setBody(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === "Enter" && (event.ctrlKey || event.metaKey) && !event.nativeEvent.isComposing) {
                event.preventDefault();
                event.currentTarget.form?.requestSubmit();
              }
            }}
            maxLength={BODY_MAX_LENGTH}
            rows={3}
            aria-label={m.updateBodyLabel}
            placeholder={m.updateBodyPlaceholder}
            className="min-h-20 resize-y border-0 p-0 shadow-none focus-visible:ring-0 dark:bg-transparent"
          />
          <div className="flex items-center justify-between gap-2">
            <ChipSelect label={m.propHealth} value={health} options={healthOptions} onChange={setHealth} />
            <Button type="submit" size="sm" disabled={mutation.isPending} className="max-md:h-11">
              {mutation.isPending ? <Spinner data-icon="inline-start" /> : null}
              {mutation.isPending ? m.posting : m.postUpdate}
            </Button>
          </div>
        </form>
      ) : null}
      {updates.length === 0 ? (
        <p className="text-[0.8125rem] leading-5 text-muted-foreground">
          {canEdit ? m.updatesEmpty : m.updatesEmptyReadOnly}
        </p>
      ) : (
        <ol className="flex flex-col gap-2">
          {updates.map((update) => (
            <li key={update.id} className="flex flex-col gap-2 rounded-lg border p-3">
              <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-[0.8125rem] leading-5">
                <HealthBadge health={update.health} />
                <span className="flex min-w-0 items-center gap-1.5">
                  <PersonAvatar person={update.author} />
                  <span className="truncate">{update.author.displayName ?? update.author.loginId}</span>
                </span>
                <time
                  dateTime={update.createdAt}
                  title={new Date(update.createdAt).toLocaleString("ko-KR")}
                  // Rendered on the server and again in the browser, a minute apart at most.
                  suppressHydrationWarning
                  className="text-muted-foreground"
                >
                  {timeAgo(update.createdAt)}
                </time>
              </div>
              {update.body ? <p className="text-sm whitespace-pre-wrap">{update.body}</p> : null}
            </li>
          ))}
        </ol>
      )}
    </section>
  );
}
