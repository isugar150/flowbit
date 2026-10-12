"use client";

import { useEffect, useRef, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { ChevronRightIcon } from "lucide-react";
import { RichTextEditor, type RichDoc } from "@/components/editor/rich-text-editor";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogTitle } from "@/components/ui/dialog";
import { Kbd } from "@/components/ui/kbd";
import { Spinner } from "@/components/ui/spinner";
import { FormAlert } from "@/features/auth/components/form-alert";
import type { Milestone } from "@/features/milestones/types";
import type { UserSummary } from "@/features/members/types";
import type { ProjectMember, ProjectSummary } from "@/features/projects/types";
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
  projectKey: null,
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
  defaultProjectKey,
  members: defaultMembers,
  milestones: defaultMilestones,
  labels,
  defaultMilestoneId = null,
  defaultDates = null,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** Projects the issue can be linked to. */
  projects: ProjectSummary[];
  /** The project the dialog was opened in; null on the list of every issue. */
  defaultProjectKey: string | null;
  /** Who can be assigned and the milestones, for `defaultProjectKey` (everyone and none when it is null). */
  members: ProjectMember[];
  milestones: Milestone[];
  /** Every label in the workspace. */
  labels: Label[];
  /** Pre-selected when the list is filtered to one milestone. */
  defaultMilestoneId?: string | null;
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
  const titleRef = useRef<HTMLInputElement>(null);

  const resetBody = () => {
    content.current = null;
    setRevision((current) => current + 1);
  };

  // Every time the dialog opens (button, `C`), start from the list's current milestone filter and
  // without the previous attempt's messages. Adjusted while rendering, since the dialog is opened
  // from outside and its own onOpenChange does not run for that.
  const [wasOpen, setWasOpen] = useState(open);
  if (open !== wasOpen) {
    setWasOpen(open);
    if (open) {
      setProperties((current) => ({
        ...current,
        projectKey: defaultProjectKey,
        milestoneId: defaultMilestoneId,
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
      setProperties({ ...DEFAULT_PROPERTIES, projectKey: defaultProjectKey });
      resetBody();
      onOpenChange(false);
    }
  });

  // Who can be assigned and which milestones exist follow the chosen project. For the project the
  // dialog was opened in they came with the page; for another one they are fetched.
  const projectKey = properties.projectKey;
  const other = projectKey !== defaultProjectKey;
  const fetched = useQuery({
    queryKey: ["new-issue-project", projectKey],
    enabled: open && other,
    queryFn: async (): Promise<{ members: ProjectMember[]; milestones: Milestone[] }> => {
      if (projectKey === null) {
        const users = await apiFetch<UserSummary[]>("/api/v1/users");
        return { members: users.map((person) => ({ ...person, userId: person.id, role: "EDITOR" })), milestones: [] };
      }
      const path = `/api/v1/projects/${encodeURIComponent(projectKey)}`;
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
    setProperties((current) => ({ ...current, projectKey: key, assigneeId: null, milestoneId: null }));

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
            {projectKey ? (
              <>
                <Badge variant="secondary" className="max-w-48 truncate">
                  {projects.find((project) => project.key === projectKey)?.name ?? projectKey}
                </Badge>
                <ChevronRightIcon className="size-3.5 text-muted-foreground" aria-hidden="true" />
              </>
            ) : null}
            <span className="font-medium">{m.new}</span>
          </div>
          <div className="flex flex-col md:flex-row">
          <div className="flex min-w-0 flex-1 flex-col gap-2 px-4 pt-3 pb-3">
            <input
              ref={titleRef}
              value={title}
              placeholder={m.titlePlaceholder}
              aria-label={m.titleLabel}
              aria-invalid={titleError ? true : undefined}
              aria-describedby={titleError ? "new-issue-title-error" : undefined}
              maxLength={200}
              autoComplete="off"
              onChange={(event) => {
                setTitle(event.target.value);
                setTitleError(false);
              }}
              onKeyDown={(event) => {
                // Plain Enter must not create a half-written issue.
                if (event.key === "Enter" && !event.ctrlKey && !event.metaKey) event.preventDefault();
              }}
              className="w-full bg-transparent text-lg leading-7 font-semibold outline-none placeholder:font-medium placeholder:text-muted-foreground"
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
                if ("projectKey" in changes) setProject(changes.projectKey ?? null);
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
              {mutation.isPending ? null : <Kbd className="max-md:hidden">Ctrl ↵</Kbd>}
            </Button>
          </div>
        </form>
      </DialogContent>
    </Dialog>
  );
}
