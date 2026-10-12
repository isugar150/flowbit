"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { FileTextIcon, PlusIcon } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Empty, EmptyContent, EmptyDescription, EmptyHeader, EmptyMedia, EmptyTitle } from "@/components/ui/empty";
import { Kbd } from "@/components/ui/kbd";
import { FormAlert } from "@/features/auth/components/form-alert";
import type { ProjectMember } from "@/features/projects/types";
import { apiErrorMessage } from "@/lib/api/error-message";
import { useRefreshingMutation } from "@/lib/use-refreshing-mutation";
import { messages } from "@/messages";
import { updateIssue } from "../api";
import { PresetIcon, PriorityIcon, STATUS_ORDER, StatusIcon, formatDueDate, presetLabel, priorityLabel, statusLabel } from "../meta";
import { PersonAvatar, statusOptions } from "../options";
import type { Issue } from "../types";
import { ChipSelect } from "./chip-select";
import { NewIssueDialog } from "./new-issue-dialog";

const m = messages.issues;

function isTyping(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false;
  return target.isContentEditable || ["INPUT", "TEXTAREA", "SELECT"].includes(target.tagName);
}

/** The issues of one project, grouped by status, with the new-issue dialog and its `C` shortcut. */
export function IssuesView({
  projectKey,
  issues,
  members,
  canEdit,
}: {
  projectKey: string;
  issues: Issue[];
  members: ProjectMember[];
  /** False for the view-only role: the list is read-only and nothing can be created. */
  canEdit: boolean;
}) {
  const [dialogOpen, setDialogOpen] = useState(false);
  const mutation = useRefreshingMutation(updateIssue);

  useEffect(() => {
    if (!canEdit) return;
    const onKeyDown = (event: KeyboardEvent) => {
      // `code` is the physical key, so this also works while a Korean input method is active.
      if (event.code !== "KeyC" || event.ctrlKey || event.metaKey || event.altKey || event.isComposing) return;
      if (isTyping(event.target) || document.querySelector("[role=dialog]")) return;
      event.preventDefault();
      setDialogOpen(true);
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [canEdit]);

  const newIssueButton = canEdit ? (
    <Button className="max-md:h-11" onClick={() => setDialogOpen(true)}>
      <PlusIcon data-icon="inline-start" />
      {m.new}
      <Kbd className="max-md:hidden">C</Kbd>
    </Button>
  ) : null;

  const groups = STATUS_ORDER.map((status) => ({ status, items: issues.filter((issue) => issue.status === status) })).filter(
    (group) => group.items.length > 0,
  );

  return (
    <>
      {issues.length === 0 ? (
        <div className="flex flex-1 items-center justify-center p-6">
          <Empty>
            <EmptyHeader>
              <EmptyMedia variant="icon">
                <FileTextIcon />
              </EmptyMedia>
              <EmptyTitle>{m.emptyTitle}</EmptyTitle>
              <EmptyDescription>{canEdit ? m.emptyDescription : m.emptyReadOnly}</EmptyDescription>
            </EmptyHeader>
            {newIssueButton ? <EmptyContent>{newIssueButton}</EmptyContent> : null}
          </Empty>
        </div>
      ) : (
        <div className="flex flex-col">
          <div className="flex items-center justify-end px-4 pb-2">{newIssueButton}</div>
          {mutation.isError ? (
            <div className="px-4 pb-2">
              <FormAlert>{apiErrorMessage(mutation.error)}</FormAlert>
            </div>
          ) : null}
          {groups.map((group) => (
            <section key={group.status} aria-label={statusLabel(group.status)}>
              <h2 className="flex h-9 items-center gap-2 border-y bg-muted px-5 text-[0.8125rem] leading-5 font-medium">
                <StatusIcon status={group.status} />
                {statusLabel(group.status)}
                <span className="font-normal text-muted-foreground tabular-nums">{group.items.length}</span>
              </h2>
              <ul>
                {group.items.map((issue) => (
                  <li key={issue.id} className="flex h-10 items-center gap-2 border-b pr-5 pl-3 hover:bg-muted max-md:h-12">
                    {canEdit ? (
                      <ChipSelect
                        iconOnly
                        label={m.statusOf(issue.key)}
                        value={issue.status}
                        options={statusOptions}
                        disabled={mutation.isPending}
                        onChange={(status) =>
                          mutation.mutate({ projectKey, number: issue.number, changes: { status } })
                        }
                      />
                    ) : (
                      <span className="flex size-7 items-center justify-center">
                        <StatusIcon status={issue.status} />
                      </span>
                    )}
                    <span title={priorityLabel(issue.priority)} className="flex shrink-0">
                      <PriorityIcon priority={issue.priority} />
                      <span className="sr-only">{priorityLabel(issue.priority)}</span>
                    </span>
                    <span className="w-16 shrink-0 truncate text-[0.8125rem] leading-5 text-muted-foreground tabular-nums">
                      {issue.key}
                    </span>
                    <Link
                      href={`/project/${projectKey}/issues/${issue.number}`}
                      className="min-w-0 flex-1 truncate rounded-sm font-medium outline-none focus-visible:ring-3 focus-visible:ring-ring/50"
                    >
                      {issue.title}
                    </Link>
                    {issue.preset ? (
                      <span className="hidden shrink-0 items-center gap-1 text-[0.8125rem] leading-5 text-muted-foreground sm:flex">
                        <PresetIcon preset={issue.preset} />
                        {presetLabel(issue.preset)}
                      </span>
                    ) : null}
                    {issue.dueDate ? (
                      <span className="hidden shrink-0 text-[0.8125rem] leading-5 text-muted-foreground tabular-nums sm:block">
                        {formatDueDate(issue.dueDate)}
                      </span>
                    ) : null}
                    {issue.assignee ? (
                      <span title={issue.assignee.displayName ?? issue.assignee.loginId} className="flex shrink-0">
                        <PersonAvatar person={issue.assignee} />
                        <span className="sr-only">{issue.assignee.displayName ?? issue.assignee.loginId}</span>
                      </span>
                    ) : null}
                  </li>
                ))}
              </ul>
            </section>
          ))}
        </div>
      )}
      {canEdit ? (
        <NewIssueDialog open={dialogOpen} onOpenChange={setDialogOpen} projectKey={projectKey} members={members} />
      ) : null}
    </>
  );
}
