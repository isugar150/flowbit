"use client";

import { useState } from "react";
import { ChevronDownIcon, PlusIcon } from "lucide-react";
import { Button } from "@/components/ui/button";
import { FormAlert } from "@/features/auth/components/form-alert";
import type { ProjectMember } from "@/features/projects/types";
import { apiErrorMessage } from "@/lib/api/error-message";
import { useOptimisticMutation, useRefreshingMutation } from "@/lib/use-refreshing-mutation";
import { messages } from "@/messages";
import { createIssue, updateIssue } from "../api";
import type { Issue, IssueChanges, IssueDetail } from "../types";
import { IssueListHeader, IssueListRow, type IssueLineEditing } from "./issue-list-row";

const m = messages.issues.subIssues;

/** How many sub-issues show before "더 보기" (the user's call, 2026-10-15). */
const SHOWN = 5;

/**
 * The issues that hang under this one, with how many are done and a progress bar. They are lines
 * of the issue list, with its column names (the user's call, 2026-10-15), sized to the panel. The
 * first five show; "더 보기" opens the rest. Status, priority, due date and assignees are changed
 * right on a line, which shows the change at once. A sub-issue linked to another project than this
 * one is read-only here: its people and rights are that project's. A new one needs only a title;
 * it is always in its parent's project, starts with its parent's assignees, and is
 * otherwise an ordinary issue (D-129).
 */
export function IssueSubIssues({
  issue,
  canEdit,
  members,
}: {
  issue: IssueDetail;
  canEdit: boolean;
  /** Who can be given an issue in this issue's project. */
  members: ProjectMember[];
}) {
  const [adding, setAdding] = useState(false);
  const [title, setTitle] = useState("");
  const [expanded, setExpanded] = useState(false);
  const mutation = useRefreshingMutation(createIssue, () => {
    setTitle("");
    // A new sub-issue comes last; the list opens so that it can be seen.
    setExpanded(true);
  });
  const changes = useOptimisticMutation(
    issue.subIssues,
    (current, { number, changes: change }: { number: number; changes: IssueChanges }) =>
      current.map((child) => (child.number === number ? applyChange(child, change, members) : child)),
    updateIssue,
  );
  const subIssues = changes.value;
  const total = subIssues.length;
  const done = subIssues.filter((child) => child.status === "DONE").length;
  // The same count as "1/2" beside it: done out of every sub-issue.
  const percent = total === 0 ? 0 : Math.round((done / total) * 100);
  const shown = expanded ? subIssues : subIssues.slice(0, SHOWN);
  const more = total - SHOWN;
  const sameProject = (child: Issue) => (child.project?.id ?? null) === (issue.project?.id ?? null);
  const editing: IssueLineEditing = {
    members: (child) => (sameProject(child) ? members : null),
    onChange: (number, change) => changes.mutate({ number, changes: change }),
  };

  const submit = () => {
    if (!title.trim() || mutation.isPending) return;
    // The project is the parent's, and the assignees start as the parent's (D-129).
    mutation.mutate({
      title,
      content: null,
      status: "BACKLOG",
      priority: "NONE",
      startDate: null,
      dueDate: null,
      labelIds: [],
      parentId: issue.id,
    });
  };

  // Sub-issues stop at a fixed depth; the deepest ones cannot be split further.
  const canAdd = canEdit && issue.canAddSubIssue;

  if (issue.subIssues.length === 0 && !canAdd) return null;

  return (
    <section data-presence-id="sub-issues" aria-labelledby="sub-issues-title" className="flex flex-col gap-1">
      <div className="flex h-8 items-center gap-2">
        <h3 id="sub-issues-title" className="text-[0.8125rem] leading-5 font-medium text-muted-foreground">
          {m.heading}
        </h3>
        {total > 0 ? (
          <span className="flex items-center gap-2 text-[0.8125rem] leading-5 text-muted-foreground tabular-nums">
            <span
              role="progressbar"
              aria-valuemin={0}
              aria-valuemax={100}
              aria-valuenow={percent}
              aria-label={m.progressBar(percent)}
              className="h-1.5 w-24 overflow-hidden rounded-full bg-muted-foreground/20 max-sm:w-16"
            >
              <span className="block h-full rounded-full bg-primary transition-[width]" style={{ width: `${percent}%` }} />
            </span>
            <span title={m.progress(done, total)}>
              {done}/{total}
            </span>
            <span className="text-foreground">{percent}%</span>
          </span>
        ) : null}
        <span className="flex-1" />
        {canAdd ? (
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
      {changes.isError ? <FormAlert>{apiErrorMessage(changes.error)}</FormAlert> : null}
      {total > 0 ? (
        <div className="@container">
          <IssueListHeader showProject={false} layout="panel" editable={canEdit} className="border-b px-1" />
          <ul className="flex flex-col">
            {shown.map((child) => (
              <IssueListRow
                key={child.id}
                issue={child}
                depth={0}
                layout="panel"
                showProject={false}
                editable={canEdit && sameProject(child)}
                editing={editing}
                onStatusChange={(number, statusId) => changes.mutate({ number, changes: { statusId } })}
                className="px-1"
                presence="sub-issue"
              />
            ))}
          </ul>
        </div>
      ) : null}
      {more > 0 ? (
        <Button
          type="button"
          variant="ghost"
          size="sm"
          className="self-start text-muted-foreground max-md:h-11"
          aria-expanded={expanded}
          onClick={() => setExpanded((current) => !current)}
        >
          <ChevronDownIcon data-icon="inline-start" className={expanded ? "rotate-180" : undefined} />
          {expanded ? m.showLess : m.showMore(more)}
        </Button>
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

/** A sub-issue with a change applied, as the server will send it back. */
function applyChange(child: Issue, change: IssueChanges, members: ProjectMember[]): Issue {
  const { assigneeIds, ...fields } = change;
  const next = { ...child, ...fields } as Issue;
  if (assigneeIds !== undefined) {
    next.assignees = assigneeIds.flatMap((id) => {
      const known = child.assignees.find((person) => person.id === id);
      if (known) return [known];
      return members
        .filter((member) => member.userId === id)
        .map((member) => ({ id: member.userId, loginId: member.loginId, displayName: member.displayName, avatarUrl: member.avatarUrl }));
    });
  }
  return next;
}
