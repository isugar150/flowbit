"use client";

import { useEffect, useMemo } from "react";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowUpRightIcon, DiamondIcon } from "lucide-react";
import { DueDate } from "@/components/dates";
import { RichTextEditor, type RichDoc } from "@/components/editor/rich-text-editor";
import { SaveStatus } from "@/components/editor/save-status";
import { PersonMark } from "@/components/person";
import { Dialog, DialogContent, DialogDescription, DialogTitle } from "@/components/ui/dialog";
import { Skeleton } from "@/components/ui/skeleton";
import { DateChip } from "@/features/issues/components/date-chips";
import { updateIssue } from "@/features/issues/api";
import { ChipSelect } from "@/features/issues/components/chip-select";
import { PriorityMark, StatusMark } from "@/features/issues/components/issue-row";
import { SubIssueIndent } from "@/features/issues/components/sub-issue-rows";
import { STATUSES, STATUS_ORDER, StatusIcon, statusLabel } from "@/features/issues/meta";
import { arrangeBySubIssues } from "@/features/issues/sub-issue-tree";
import type { Issue, IssueStatus } from "@/features/issues/types";
import { apiFetch } from "@/lib/api/client";
import { useAutosave } from "@/lib/use-autosave";
import { useRemoteValue } from "@/lib/use-remote-value";
import { cn } from "@/lib/utils";
import { messages } from "@/messages";
import { updateMilestone } from "../api";
import { milestoneProgress, type Milestone } from "../types";

const statusOptions = STATUSES.map((status) => ({
  value: status,
  label: statusLabel(status),
  icon: <StatusIcon status={status} />,
}));

const m = messages.milestones;
const NAME_MAX_LENGTH = 80;

/** The name field is saved on blur, so while it has focus the person's typing wins. */
const isFocused = (input: HTMLInputElement) => document.activeElement === input;

/**
 * One milestone opened from the project overview. On the left, what it is and a document where
 * the people working on it write down where things stand; on the right, the issues in it. People
 * who may edit change the name and the target date here, and just type in the document: there is
 * no edit mode and no save button.
 */
export function MilestoneDetailDialog({
  projectId,
  milestone,
  canEdit,
  onChange,
  onClose,
}: {
  projectId: string;
  milestone: Milestone;
  canEdit: boolean;
  /** A new name or target date; shown at once by whoever holds the list. */
  onChange: (changes: { name?: string; targetDate?: string | null }) => void;
  onClose: () => void;
}) {
  const router = useRouter();
  const notes = useAutosave((content: RichDoc | null) => updateMilestone({ projectId, id: milestone.id, changes: { content } }));
  const percent = milestoneProgress(milestone);
  const done = milestone.issueCount > 0 && milestone.doneCount === milestone.issueCount;
  const nameInput = useRemoteValue<HTMLInputElement>(milestone.name, isFocused);
  // Read each time the milestone is opened: issues move in and out of it elsewhere.
  const issuesKey = ["milestone-issues", projectId, milestone.id];
  const queryClient = useQueryClient();
  const issues = useQuery({
    queryKey: issuesKey,
    queryFn: async () => {
      const all = await apiFetch<Issue[]>(`/api/v1/projects/${encodeURIComponent(projectId)}/issues`);
      return all
        .filter((issue) => issue.milestone?.id === milestone.id)
        .sort((a, b) => STATUS_ORDER.indexOf(a.status) - STATUS_ORDER.indexOf(b.status) || a.number - b.number);
    },
    staleTime: 0,
  });
  // The list is read by this dialog, not by the page, so the page reading itself again does not
  // renew it. Both signs that an issue may have changed do: the overview arriving anew (a save
  // here or by someone else hands over a new `milestone`) and the address changing (an issue
  // opened from the list was closed).
  const pathname = usePathname();
  const refetchIssues = issues.refetch;
  useEffect(() => {
    // Not while a reading is already on its way (the first one, when the dialog opens).
    void refetchIssues({ cancelRefetch: false });
  }, [milestone, pathname, refetchIssues]);
  // Sub-issues sit under their parents, one step in for every level, as in the issue lists.
  const rows = useMemo(() => arrangeBySubIssues(issues.data ?? []), [issues.data]);

  // The status shows at once (the list is this dialog's own reading, so its cache is changed
  // first); the overview behind is then read again for the progress, which renews the list too.
  const changeStatus = (number: number, status: IssueStatus) => {
    queryClient.setQueryData<Issue[]>(issuesKey, (current) =>
      current?.map((issue) => (issue.number === number ? { ...issue, status } : issue)),
    );
    void updateIssue({ number, changes: { status } })
      .catch(() => refetchIssues())
      .then(() => router.refresh());
  };

  const commitName = (input: HTMLInputElement) => {
    const name = input.value.trim();
    if (!name) input.value = milestone.name;
    else if (name !== milestone.name) onChange({ name });
  };

  return (
    <Dialog
      open
      onOpenChange={(open) => {
        if (open) return;
        // What was typed last is sent before the overview is read again, so the line behind
        // (and the next opening of this dialog) starts from what was written.
        void notes.flush().then(() => router.refresh());
        onClose();
      }}
    >
      {/* Hung from the top rather than centred, so it does not jump as the document grows. */}
      <DialogContent className="top-[6dvh] translate-y-0 gap-0 p-0 sm:max-w-2xl lg:max-w-5xl">
        {/* The scroll box is inside, not the dialog itself, so the editor's menus are not clipped. */}
        <div className="flex max-h-[88dvh] min-h-64 flex-col overflow-y-auto overscroll-contain rounded-xl lg:flex-row">
          <div className="flex min-w-0 flex-1 flex-col gap-5 p-5 pt-6">
            <div className="flex flex-col gap-3">
              <DialogTitle className="sr-only">{milestone.name}</DialogTitle>
              <DialogDescription className="sr-only">{m.detailDescription}</DialogDescription>
              <div className="flex items-center gap-2 pr-8">
                <DiamondIcon className={cn("size-5 shrink-0", done ? "fill-primary text-primary" : "text-muted-foreground")} aria-hidden="true" />
                {canEdit ? (
                  <input
                    ref={nameInput}
                    defaultValue={milestone.name}
                    maxLength={NAME_MAX_LENGTH}
                    aria-label={m.nameField}
                    autoComplete="off"
                    onBlur={(event) => commitName(event.currentTarget)}
                    onKeyDown={(event) => {
                      if (event.key === "Enter" && !event.nativeEvent.isComposing) event.currentTarget.blur();
                      if (event.key === "Escape") {
                        // Gives the typing up; the dialog stays open.
                        event.stopPropagation();
                        event.currentTarget.value = milestone.name;
                        event.currentTarget.blur();
                      }
                    }}
                    className="min-w-0 flex-1 rounded-sm bg-transparent text-lg leading-7 font-semibold outline-none focus-visible:ring-3 focus-visible:ring-ring/50"
                  />
                ) : (
                  <h2 className="min-w-0 truncate text-lg leading-7 font-semibold">{milestone.name}</h2>
                )}
              </div>
              <div className="flex flex-wrap items-center gap-x-4 gap-y-2 text-[0.8125rem] leading-5">
                <span className="flex items-center gap-2">
                  <span
                    role="progressbar"
                    aria-valuemin={0}
                    aria-valuemax={100}
                    aria-valuenow={percent}
                    aria-label={m.progress(percent)}
                    className="h-1.5 w-28 overflow-hidden rounded-full bg-muted"
                  >
                    <span className="block h-full rounded-full bg-primary" style={{ width: `${percent}%` }} />
                  </span>
                  <span className="tabular-nums">{percent}%</span>
                </span>
                <span className="text-muted-foreground tabular-nums">{m.issues(milestone.doneCount, milestone.issueCount)}</span>
                {canEdit || milestone.targetDate ? (
                  <DateChip
                    label={m.targetDate}
                    placeholder={m.noTargetDate}
                    value={milestone.targetDate}
                    disabled={!canEdit}
                    onChange={(targetDate) => onChange({ targetDate })}
                  />
                ) : (
                  <span className="text-muted-foreground">{m.noTarget}</span>
                )}
              </div>
            </div>
            <section className="flex flex-col gap-2">
              <div className="flex items-center justify-between gap-3">
                <h3 className="text-[0.8125rem] leading-5 font-medium text-muted-foreground">{m.notesTitle}</h3>
                <SaveStatus status={notes.status} />
              </div>
              {canEdit || milestone.content ? (
                <RichTextEditor
                  initialContent={milestone.content}
                  editable={canEdit}
                  placeholder={m.notesPlaceholder}
                  ariaLabel={m.notesLabel}
                  className="min-h-48"
                  onChange={notes.schedule}
                  keepLocal={notes.isDirty}
                />
              ) : (
                <p className="text-muted-foreground">{m.noNotes}</p>
              )}
            </section>
          </div>
          <aside
            aria-labelledby="milestone-issues-title"
            className="flex w-full shrink-0 flex-col gap-2 border-t p-5 lg:w-96 lg:border-t-0 lg:border-l lg:pt-6"
          >
            <div className="flex items-center justify-between gap-2 lg:pr-8">
              <h3 id="milestone-issues-title" className="text-[0.8125rem] leading-5 font-medium text-muted-foreground">
                {m.issuesTitle}
                {issues.data ? <span className="ml-1.5 font-normal tabular-nums">{issues.data.length}</span> : null}
              </h3>
              <Link
                href={`/project/${projectId}/issues?milestone=${milestone.id}`}
                className="flex items-center gap-1 rounded-sm text-[0.8125rem] leading-5 text-muted-foreground outline-none hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50"
              >
                {m.openInList}
                <ArrowUpRightIcon className="size-3.5" aria-hidden="true" />
              </Link>
            </div>
            {issues.isPending ? (
              <div className="flex flex-col gap-2" aria-busy="true">
                <span className="sr-only">{m.issuesLoading}</span>
                <Skeleton className="h-8" />
                <Skeleton className="h-8" />
                <Skeleton className="h-8" />
              </div>
            ) : issues.isError ? (
              <p role="alert" className="text-[0.8125rem] leading-5 text-destructive">
                {m.issuesFailed}
              </p>
            ) : issues.data.length === 0 ? (
              <p className="text-[0.8125rem] leading-5 text-muted-foreground">{m.noIssues}</p>
            ) : (
              <ul className="-mx-2 flex flex-col">
                {rows.map((row) =>
                  row.kind === "context" ? (
                    // The parent is in another milestone (or none); it only gives its sub-issues their place.
                    <li key={`parent-${row.parent.number}`} className="flex h-9 items-center gap-2 px-2 max-md:h-11">
                      <span className="shrink-0 text-xs leading-4 text-muted-foreground tabular-nums">{row.parent.key}</span>
                      <Link
                        href={`/issue/${row.parent.key}`}
                        title={messages.issues.subIssues.parentContext}
                        className="min-w-0 flex-1 truncate rounded-sm text-muted-foreground outline-none hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50"
                      >
                        {row.parent.title}
                      </Link>
                    </li>
                  ) : (
                    <li key={row.issue.id} className="flex h-9 items-center gap-2 rounded-md px-2 hover:bg-muted max-md:h-11">
                      <SubIssueIndent depth={row.depth} />
                      {canEdit ? (
                        <ChipSelect
                          iconOnly
                          label={messages.issues.statusOf(row.issue.key)}
                          value={row.issue.status}
                          options={statusOptions}
                          onChange={(status) => changeStatus(row.issue.number, status)}
                        />
                      ) : (
                        <span className="flex size-7 items-center justify-center">
                          <StatusMark status={row.issue.status} />
                        </span>
                      )}
                      {/* The key and the title are one link as tall as the line, so the whole middle of it opens the issue. */}
                      <Link
                        href={`/issue/${row.issue.key}`}
                        className="flex h-full min-w-0 flex-1 items-center gap-2 rounded-sm outline-none focus-visible:ring-3 focus-visible:ring-ring/50"
                      >
                        <span className="shrink-0 text-xs leading-4 text-muted-foreground tabular-nums">{row.issue.key}</span>
                        <span className="min-w-0 truncate font-medium">{row.issue.title}</span>
                      </Link>
                      {/* The same columns on every line, with or without a value, so they line up. */}
                      <span className="w-14 shrink-0 text-right text-xs leading-4 text-muted-foreground tabular-nums">
                        {row.issue.dueDate ? <DueDate value={row.issue.dueDate} /> : null}
                      </span>
                      <PriorityMark priority={row.issue.priority} />
                      <span className="flex w-5 shrink-0 justify-center">
                        {row.issue.assignee ? <PersonMark person={row.issue.assignee} /> : null}
                      </span>
                    </li>
                  ),
                )}
              </ul>
            )}
          </aside>
        </div>
      </DialogContent>
    </Dialog>
  );
}
