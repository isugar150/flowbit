"use client";

import { useState } from "react";
import Link from "next/link";
import { CircleUserRoundIcon, PlusIcon } from "lucide-react";
import { DueDate } from "@/components/dates";
import { Button } from "@/components/ui/button";
import { apiErrorMessage } from "@/lib/api/error-message";
import { useRefreshingMutation } from "@/lib/use-refreshing-mutation";
import { messages } from "@/messages";
import { createIssue } from "../api";
import { PriorityIcon, StatusIcon, priorityLabel, statusLabel } from "../meta";
import { PersonAvatar } from "../options";
import type { IssueDetail } from "../types";
import { LabelPill } from "./label-chip";
import { SubIssueProgress } from "./sub-issue-rows";

const m = messages.issues.subIssues;

/**
 * The issues that hang under this one, with how many are done. A new one needs only a title; it
 * is linked to the same project as its parent and is otherwise an ordinary issue.
 */
export function IssueSubIssues({ issue, canEdit }: { issue: IssueDetail; canEdit: boolean }) {
  const [adding, setAdding] = useState(false);
  const [title, setTitle] = useState("");
  const mutation = useRefreshingMutation(createIssue, () => setTitle(""));
  const done = issue.subIssues.filter((child) => child.status === "DONE").length;

  const submit = () => {
    if (!title.trim() || mutation.isPending) return;
    mutation.mutate({
      title,
      content: null,
      status: "BACKLOG",
      priority: "NONE",
      assigneeId: null,
      startDate: null,
      dueDate: null,
      milestoneId: null,
      labelIds: [],
      projectId: issue.project?.id ?? null,
      parentId: issue.id,
    });
  };

  if (issue.subIssues.length === 0 && !canEdit) return null;

  return (
    <section data-presence-id="sub-issues" aria-labelledby="sub-issues-title" className="flex flex-col gap-1">
      <div className="flex h-8 items-center gap-2">
        <h3 id="sub-issues-title" className="text-[0.8125rem] leading-5 font-medium text-muted-foreground">
          {m.heading}
        </h3>
        {issue.subIssues.length > 0 ? (
          <span className="text-[0.8125rem] leading-5 text-muted-foreground tabular-nums">
            {done}/{issue.subIssues.length}
          </span>
        ) : null}
        <span className="flex-1" />
        {canEdit ? (
          <Button
            type="button"
            variant="ghost"
            size="icon-sm"
            className="text-muted-foreground max-md:size-11"
            aria-label={m.add}
            title={m.add}
            aria-expanded={adding}
            onClick={() => setAdding((current) => !current)}
          >
            <PlusIcon />
          </Button>
        ) : null}
      </div>
      {issue.subIssues.length > 0 ? (
        <ul className="flex flex-col">
          {issue.subIssues.map((child) => (
            <li key={child.id} className="flex h-9 items-center gap-2 rounded-md px-1 hover:bg-muted max-md:h-11">
              <span title={statusLabel(child.status)} className="flex shrink-0">
                <StatusIcon status={child.status} />
                <span className="sr-only">{statusLabel(child.status)}</span>
              </span>
              <span className="shrink-0 text-[0.8125rem] leading-5 text-muted-foreground tabular-nums">{child.key}</span>
              <Link
                href={`/issue/${child.key}`}
                className="min-w-0 flex-1 truncate rounded-sm font-medium outline-none focus-visible:ring-3 focus-visible:ring-ring/50"
              >
                {child.title}
              </Link>
              <SubIssueProgress issue={child} />
              {/* The right side always says something: labels and a due date when there are any, then priority and who has it. */}
              <span className="hidden shrink-0 items-center gap-1 sm:flex">
                {child.labels.slice(0, 2).map((label) => (
                  <LabelPill key={label.id} label={label} />
                ))}
              </span>
              {child.dueDate ? (
                <span className="hidden shrink-0 text-xs leading-4 text-muted-foreground tabular-nums sm:block">
                  <DueDate value={child.dueDate} />
                </span>
              ) : null}
              <span title={priorityLabel(child.priority)} className="flex shrink-0">
                <PriorityIcon priority={child.priority} />
                <span className="sr-only">{priorityLabel(child.priority)}</span>
              </span>
              {child.assignee ? (
                <span title={child.assignee.displayName ?? child.assignee.loginId} className="flex shrink-0">
                  <PersonAvatar person={child.assignee} />
                  <span className="sr-only">{child.assignee.displayName ?? child.assignee.loginId}</span>
                </span>
              ) : (
                <span title={messages.issues.noAssignee} className="flex shrink-0">
                  <CircleUserRoundIcon className="size-4.5 text-muted-foreground/60" aria-hidden="true" />
                  <span className="sr-only">{messages.issues.noAssignee}</span>
                </span>
              )}
            </li>
          ))}
        </ul>
      ) : null}
      {adding ? (
        <form
          className="flex items-center gap-2"
          onSubmit={(event) => {
            event.preventDefault();
            submit();
          }}
        >
          <input
            // Appears only after pressing "+", so taking focus is what the person expects.
            autoFocus
            value={title}
            maxLength={200}
            placeholder={m.titlePlaceholder}
            aria-label={m.titlePlaceholder}
            autoComplete="off"
            onChange={(event) => setTitle(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === "Escape") {
                event.stopPropagation();
                setAdding(false);
              }
            }}
            className="h-9 min-w-0 flex-1 rounded-md border bg-background px-2.5 outline-none placeholder:text-muted-foreground focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 max-md:h-11"
          />
          <Button type="submit" variant="outline" className="h-9 max-md:h-11" disabled={!title.trim() || mutation.isPending}>
            {m.create}
          </Button>
        </form>
      ) : null}
      {mutation.isError ? (
        <p role="alert" className="text-[0.8125rem] leading-5 text-destructive">
          {apiErrorMessage(mutation.error)}
        </p>
      ) : null}
    </section>
  );
}
