"use client";

import { useEffect, useRef, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { ChevronRightIcon } from "lucide-react";
import { RichTextEditor, type RichDoc } from "@/components/editor/rich-text-editor";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogTitle } from "@/components/ui/dialog";
import { ModKey } from "@/components/mod-key";
import { Kbd } from "@/components/ui/kbd";
import { Spinner } from "@/components/ui/spinner";
import { FormAlert } from "@/features/auth/components/form-alert";
import type { Milestone } from "@/features/milestones/types";
import type { UserSummary } from "@/features/members/types";
import { usersAsMembers, type ProjectMember, type ProjectSummary } from "@/features/projects/types";
import { apiFetch } from "@/lib/api/client";
import { apiErrorMessage } from "@/lib/api/error-message";
import { useRefreshingMutation } from "@/lib/use-refreshing-mutation";
import { messages } from "@/messages";
import { createIssue } from "../api";
import type { Label, NewIssueInput } from "../types";
import { IssuePropertyFields } from "./issue-properties";

const m = messages.issues;

type Properties = Omit<NewIssueInput, "title" | "content">;
const DEFAULT_PROPERTIES: Properties = {
  status: "BACKLOG",
  priority: "NONE",
  assigneeId: null,
  startDate: null,
  dueDate: null,
  milestoneId: null,
  labelIds: [],
  projectId: null,
};

/**
 * New issue: a title and a body on the left, the properties in a column on the right (below on
 * a narrow screen), the same arrangement as the issue page. Only the title is required.
 * Ctrl/⌘+Enter creates it from anywhere in the form.
 */
export function NewIssueDialog({
  open,
  onOpenChange,
  projects,
  defaultProjectId,
  members: defaultMembers,
  milestones: defaultMilestones,
  labels,
  defaultMilestoneId = null,
  defaultAssigneeId = null,
  defaultDates = null,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** Projects the issue can be linked to. */
  projects: ProjectSummary[];
  /** The project the dialog was opened in; null on the list of every issue. */
  defaultProjectId: string | null;
  /** Who can be assigned and the milestones, for `defaultProjectId` (everyone and none when it is null). */
  members: ProjectMember[];
  milestones: Milestone[];
  /** Every label in the workspace. */
  labels: Label[];
  /** Pre-selected when the list is filtered to one milestone. */
  defaultMilestoneId?: string | null;
  /** Pre-selected on a list of one person's issues ("내 이슈"), so the new issue shows up there. */
  defaultAssigneeId?: string | null;
  /** Dates to start with, when the dialog is opened from a day or a span of days on the calendar. */
  defaultDates?: { startDate: string | null; dueDate: string } | null;
}) {
  const [title, setTitle] = useState("");
  const [properties, setProperties] = useState<Properties>(DEFAULT_PROPERTIES);
  // The body lives in the editor; a new `revision` restarts it empty (after "create more").
  const [revision, setRevision] = useState(0);
  const content = useRef<RichDoc | null>(null);
  const [createMore, setCreateMore] = useState(false);
  const [titleError, setTitleError] = useState(false);
  const [lastCreated, setLastCreated] = useState<string | null>(null);
  const titleRef = useRef<HTMLTextAreaElement>(null);
  // The title wraps instead of scrolling sideways: the field grows with what is typed.
  useEffect(() => {
    const field = titleRef.current;
    if (!field) return;
    field.style.height = "auto";
    field.style.height = `${field.scrollHeight}px`;
  }, [title, open]);

  const resetBody = () => {
    content.current = null;
    setRevision((current) => current + 1);
  };

  // Every time the dialog opens (button, `C`), start from the list's current milestone filter and
  // without the previous attempt's messages. Adjusted while rendering, since the dialog is opened
  // from outside and its own onOpenChange does not run for that.
  // Starts as closed even when the dialog is first rendered open (it is loaded on first use), so
  // that first opening takes the defaults too.
  const [wasOpen, setWasOpen] = useState(false);
  if (open !== wasOpen) {
    setWasOpen(open);
    if (open) {
      setProperties((current) => ({
        ...current,
        projectId: defaultProjectId,
        milestoneId: defaultMilestoneId,
        assigneeId: current.assigneeId ?? defaultAssigneeId,
        startDate: defaultDates?.startDate ?? null,
        dueDate: defaultDates?.dueDate ?? null,
      }));
      setTitleError(false);
      setLastCreated(null);
    }
  }

  const mutation = useRefreshingMutation(createIssue, (issue) => {
    setTitle("");
    if (createMore) {
      // Keep what the next issue is likely to share (status, labels, assignee); start a fresh body.
      resetBody();
      setLastCreated(issue.key);
      titleRef.current?.focus();
    } else {
      setProperties({ ...DEFAULT_PROPERTIES, projectId: defaultProjectId, assigneeId: defaultAssigneeId });
      resetBody();
      onOpenChange(false);
    }
  });

  // Who can be assigned and which milestones exist follow the chosen project. For the project the
  // dialog was opened in they came with the page; for another one they are fetched.
  const projectId = properties.projectId;
  const other = projectId !== defaultProjectId;
  const fetched = useQuery({
    queryKey: ["new-issue-project", projectId],
    enabled: open && other,
    queryFn: async (): Promise<{ members: ProjectMember[]; milestones: Milestone[] }> => {
      if (projectId === null) {
        const users = await apiFetch<UserSummary[]>("/api/v1/users");
        return { members: usersAsMembers(users), milestones: [] };
      }
      const path = `/api/v1/projects/${encodeURIComponent(projectId)}`;
      const [members, milestones] = await Promise.all([
        apiFetch<ProjectMember[]>(`${path}/members`),
        apiFetch<Milestone[]>(`${path}/milestones`),
      ]);
      return { members, milestones };
    },
  });
  const members = other ? (fetched.data?.members ?? []) : defaultMembers;
  const milestones = other ? (fetched.data?.milestones ?? []) : defaultMilestones;
  // An assignee or a milestone belongs to a project; changing the project starts those over.
  const setProject = (key: string | null) =>
    setProperties((current) => ({ ...current, projectId: key, assigneeId: null, milestoneId: null }));

  const submit = () => {
    if (mutation.isPending) return;
    if (!title.trim()) {
      setTitleError(true);
      titleRef.current?.focus();
      return;
    }
    setLastCreated(null);
    mutation.mutate({ title, content: content.current, ...properties });
  };

  const resetMutation = mutation.reset;
  useEffect(() => {
    if (open) resetMutation();
  }, [open, resetMutation]);

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      {/* The dialog exists to type a title: put the cursor there as it opens. */}
      <DialogContent className="gap-0 p-0 sm:max-w-2xl md:max-w-4xl" initialFocus={titleRef}>
        <DialogTitle className="sr-only">{m.newTitle}</DialogTitle>
        <DialogDescription className="sr-only">{m.emptyDescription}</DialogDescription>
        <form
          noValidate
          onSubmit={(event) => {
            event.preventDefault();
            submit();
          }}
          onKeyDown={(event) => {
            if (event.key === "Enter" && (event.ctrlKey || event.metaKey) && !event.nativeEvent.isComposing) {
              event.preventDefault();
              submit();
            }
          }}
          className="flex flex-col"
        >
          <div className="flex items-center gap-1 px-4 pt-3.5 text-[0.8125rem] leading-5">
            {projectId ? (
              <>
                {/* The name is cut at its end; the badge alone would centre it and lose both ends. */}
                <Badge variant="secondary" className="max-w-48 justify-start">
                  <span className="truncate">{projects.find((project) => project.id === projectId)?.name ?? projectId}</span>
                </Badge>
                <ChevronRightIcon className="size-3.5 text-muted-foreground" aria-hidden="true" />
              </>
            ) : null}
            <span className="font-medium">{m.new}</span>
          </div>
          <div className="flex flex-col md:flex-row">
          <div className="flex min-w-0 flex-1 flex-col gap-2 px-4 pt-3 pb-3">
            <textarea
              ref={titleRef}
              rows={1}
              value={title}
              placeholder={m.titlePlaceholder}
              aria-label={m.titleLabel}
              aria-invalid={titleError ? true : undefined}
              aria-describedby={titleError ? "new-issue-title-error" : undefined}
              maxLength={200}
              autoComplete="off"
              onChange={(event) => {
                // A title is one line however it was typed or pasted.
                setTitle(event.target.value.replace(/\s*\n\s*/g, " "));
                setTitleError(false);
              }}
              onKeyDown={(event) => {
                // Plain Enter must not create a half-written issue.
                if (event.key === "Enter" && !event.ctrlKey && !event.metaKey) event.preventDefault();
              }}
              className="max-h-40 w-full resize-none bg-transparent text-lg leading-7 font-semibold outline-none placeholder:font-medium placeholder:text-muted-foreground"
            />
            {titleError ? (
              <p id="new-issue-title-error" role="alert" className="text-[0.8125rem] leading-5 text-destructive">
                {m.titleRequired}
              </p>
            ) : null}
            <RichTextEditor
              key={revision}
              initialContent={null}
              placeholder={m.descriptionPlaceholder}
              ariaLabel={m.descriptionLabel}
              // -ml/pl: the scroll box reaches into the left padding so the block grip is not clipped.
              className="-ml-4 max-h-[45dvh] min-h-28 overflow-y-auto overscroll-contain pl-4 md:max-h-[55dvh] md:min-h-64"
              onChange={(doc) => {
                content.current = doc;
              }}
            />
          </div>
          {/* The same panel as on an existing issue, so making and changing look alike. */}
          <aside className="shrink-0 border-t px-5 py-4 md:w-64 md:border-t-0 md:border-l">
            <IssuePropertyFields
              values={properties}
              onChange={(changes) => {
                // An assignee or a milestone belongs to a project; changing the project starts those over.
                if ("projectId" in changes) setProject(changes.projectId ?? null);
                else setProperties((current) => ({ ...current, ...changes }));
              }}
              members={members}
              labels={labels}
              projects={projects}
              milestones={milestones}
            />
          </aside>
          </div>
          {mutation.isError ? (
            <div className="px-4 pb-2">
              <FormAlert>{apiErrorMessage(mutation.error)}</FormAlert>
            </div>
          ) : null}
          <div className="flex flex-wrap items-center justify-end gap-3 border-t px-4 py-2.5">
            {lastCreated ? (
              <p role="status" className="mr-auto text-[0.8125rem] leading-5 text-muted-foreground">
                {m.created(lastCreated)}
              </p>
            ) : null}
            <label className="flex items-center gap-2 text-[0.8125rem] leading-5 text-muted-foreground">
              <input
                type="checkbox"
                className="size-4 accent-primary"
                checked={createMore}
                onChange={(event) => setCreateMore(event.target.checked)}
              />
              {m.createMore}
            </label>
            <Button type="submit" className="max-md:h-11" disabled={mutation.isPending}>
              {mutation.isPending ? <Spinner data-icon="inline-start" aria-hidden="true" /> : null}
              {mutation.isPending ? m.creating : m.create}
              {mutation.isPending ? null : (
                <Kbd className="max-md:hidden">
                  <ModKey /> ↵
                </Kbd>
              )}
            </Button>
          </div>
        </form>
      </DialogContent>
    </Dialog>
  );
}
