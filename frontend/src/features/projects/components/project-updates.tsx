"use client";

import { useState } from "react";
import { Trash2Icon } from "lucide-react";
import { ShortcutKeys } from "@/components/shortcut-keys";
import { Button } from "@/components/ui/button";
import { Spinner } from "@/components/ui/spinner";
import { Textarea } from "@/components/ui/textarea";
import { FormAlert } from "@/features/auth/components/form-alert";
import { ChipSelect } from "@/features/issues/components/chip-select";
import { PersonAvatar, personName } from "@/components/person";
import { apiErrorMessage } from "@/lib/api/error-message";
import { useRefreshingMutation } from "@/lib/use-refreshing-mutation";
import { messages } from "@/messages";
import { deleteProjectUpdate, postProjectUpdate } from "../api";
import { HealthBadge, healthOptions } from "../meta";
import type { ProjectHealth, ProjectUpdate } from "../types";
import { TimeAgo } from "@/components/dates";

const m = messages.projects;
const BODY_MAX_LENGTH = 5000;

/**
 * Project updates on the overview: people who may edit post how the project is going, with a
 * health; the latest health is what the project list shows. Newest first. The text is optional,
 * but an update that says nothing new (no text, the same health as the last one) is not posted.
 * Whoever wrote an update, or a manager, can take it back.
 */
export function ProjectUpdates({
  projectId,
  updates,
  canEdit,
  canManage,
  currentUserId,
}: {
  projectId: string;
  updates: ProjectUpdate[];
  canEdit: boolean;
  canManage: boolean;
  currentUserId: string;
}) {
  const [health, setHealth] = useState<ProjectHealth>(updates[0]?.health ?? "ON_TRACK");
  const [body, setBody] = useState("");
  const mutation = useRefreshingMutation(postProjectUpdate, () => setBody(""));
  const removal = useRefreshingMutation(deleteProjectUpdate);
  // The first press asks, the second one deletes (the same as deleting an issue).
  const [confirming, setConfirming] = useState<string | null>(null);
  const nothingNew = body.trim() === "" && updates[0]?.health === health;

  return (
    <section className="flex flex-col gap-3" aria-labelledby="project-updates-title">
      <h3 id="project-updates-title" className="text-[0.8125rem] leading-5 font-medium text-muted-foreground">
        {m.updatesTitle}
      </h3>
      {canEdit ? (
        <form
          data-presence-id="update"
          className="flex flex-col gap-2 rounded-lg border p-3 has-[textarea:focus-visible]:border-ring has-[textarea:focus-visible]:ring-3 has-[textarea:focus-visible]:ring-ring/50"
          onSubmit={(event) => {
            event.preventDefault();
            if (!mutation.isPending && !nothingNew) mutation.mutate({ id: projectId, health, body });
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
            <Button type="submit" size="sm" disabled={mutation.isPending || nothingNew} className="max-md:h-11">
              {mutation.isPending ? <Spinner data-icon="inline-start" /> : null}
              {mutation.isPending ? m.posting : m.postUpdate}
              {mutation.isPending ? null : (
                <ShortcutKeys id="submit" />
              )}
            </Button>
          </div>
        </form>
      ) : null}
      {removal.isError ? <FormAlert>{apiErrorMessage(removal.error)}</FormAlert> : null}
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
                  <span className="truncate">{personName(update.author)}</span>
                </span>
                <time
                  dateTime={update.createdAt}
                  title={new Date(update.createdAt).toLocaleString("ko-KR")}
                  // Rendered on the server and again in the browser, a minute apart at most.
                  suppressHydrationWarning
                  className="text-muted-foreground"
                >
                  <TimeAgo iso={update.createdAt} />
                </time>
                {canManage || (canEdit && update.author.id === currentUserId) ? (
                  <Button
                    type="button"
                    variant="ghost"
                    size="sm"
                    disabled={removal.isPending}
                    aria-label={confirming === update.id ? undefined : m.deleteUpdate}
                    title={confirming === update.id ? undefined : m.deleteUpdate}
                    className={
                      confirming === update.id
                        ? "ml-auto text-destructive hover:text-destructive"
                        : "ml-auto text-muted-foreground"
                    }
                    onBlur={() => setConfirming((current) => (current === update.id ? null : current))}
                    onClick={() => {
                      if (confirming !== update.id) setConfirming(update.id);
                      else removal.mutate({ id: projectId, updateId: update.id });
                    }}
                  >
                    <Trash2Icon data-icon={confirming === update.id ? "inline-start" : undefined} />
                    {confirming === update.id ? m.confirmDeleteUpdate : null}
                  </Button>
                ) : null}
              </div>
              {update.body ? <p className="text-sm whitespace-pre-wrap">{update.body}</p> : null}
            </li>
          ))}
        </ol>
      )}
    </section>
  );
}
