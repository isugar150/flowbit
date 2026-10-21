"use client";

import { useEffect, useMemo } from "react";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowUpRightIcon, DiamondIcon } from "lucide-react";
import { DueDate } from "@/components/dates";
import { RichTextEditor, type RichDoc } from "@/components/editor/rich-text-editor";
import { milestoneTemplate } from "../outline";
import { SaveStatus } from "@/components/editor/save-status";
import { PeopleMarks } from "@/components/person";
import { Dialog, DialogContent, DialogDescription, DialogTitle } from "@/components/ui/dialog";
import { Skeleton } from "@/components/ui/skeleton";
import { BlockComments } from "@/features/content/components/block-comments";
import { ContentActivity } from "@/features/content/components/content-activity";
import { useContentFeed } from "@/features/content/use-content-feed";
import { DateChip } from "@/features/issues/components/date-chips";
import { updateIssue } from "@/features/issues/api";
import { ChipSelect } from "@/features/issues/components/chip-select";
import { PriorityMark, StatusMark } from "@/features/issues/components/issue-row";
import { SubIssueIndent } from "@/features/issues/components/sub-issue-rows";
import { STATUSES, STATUS_ORDER, StatusIcon, statusLabel } from "@/features/issues/meta";
import { arrangeBySubIssues, type IssueRow } from "@/features/issues/sub-issue-tree";
import type { Issue, IssueStatus } from "@/features/issues/types";
import { apiFetch } from "@/lib/api/client";
import { useAutosave } from "@/lib/use-autosave";
import { useRemoteValue } from "@/lib/use-remote-value";
import { cn } from "@/lib/utils";
import { messages } from "@/messages";
import { updateMilestone } from "../api";
import { ProjectStatusIcon, projectStatusLabel, projectStatusOptions } from "@/features/projects/meta";
import { mentionablesOf, type ProjectMember } from "@/features/projects/types";
import { milestoneProgress, type Milestone, type MilestoneStatus } from "../types";

const statusOptions = STATUSES.map((status) => ({
  value: status,
  label: statusLabel(status),
  icon: <StatusIcon status={status} />,
}));

const m = messages.milestones;
const NAME_MAX_LENGTH = 80;
/** The first level of sub-issues the issue list leaves out: it shows the top issue and two levels under it. */
const HIDDEN_DEPTH = 3;

type ListRow = IssueRow | { kind: "hidden"; under: string; count: number; depth: number };

/**
 * Drops the rows from `HIDDEN_DEPTH` down. In their place, under the last issue still shown above
 * them, one line says how many of the milestone's issues were left out ("외 3개").
 */
function withoutDeepRows(rows: IssueRow[]): ListRow[] {
  const shown: ListRow[] = [];
  let hidden = 0;
  let under = "";
  const flush = () => {
    if (hidden > 0) shown.push({ kind: "hidden", under, count: hidden, depth: HIDDEN_DEPTH });
    hidden = 0;
  };
  for (const row of rows) {
    if (row.depth >= HIDDEN_DEPTH) {
      // A dimmed parent is not one of the milestone's issues, so it is not counted.
      if (row.kind === "issue") hidden += 1;
      continue;
    }
    flush();
    if (row.depth === HIDDEN_DEPTH - 1) under = row.kind === "issue" ? row.issue.key : row.parent.key;
    shown.push(row);
  }
  flush();
  return shown;
}

/**
 * Keeps the first `limit` issue lines (and the lines that belong with them); the issues after
 * them are counted on one last line ("외 12개").
 */
function upTo(rows: ListRow[], limit: number): ListRow[] {
  const shown: ListRow[] = [];
  let count = 0;
  let rest = 0;
  for (const row of rows) {
    // A count of deeper issues still belongs to the issue shown just above it.
    const cut = rest > 0 || (count === limit && row.kind !== "hidden");
    if (cut) {
      rest += row.kind === "issue" ? 1 : row.kind === "hidden" ? row.count : 0;
      continue;
    }
    if (row.kind === "issue") count += 1;
    shown.push(row);
  }
  if (rest > 0) shown.push({ kind: "hidden", under: "rest", count: rest, depth: 0 });
  return shown;
}

/** The name field is saved on blur, so while it has focus the person's typing wins. */
const isFocused = (input: HTMLInputElement) => document.activeElement === input;

/** What the dialog and the panel may change: the name, the status (D-163) and the target date. */
export type MilestoneChanges = { name?: string; status?: MilestoneStatus; targetDate?: string | null };

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
  /** A new name, status or target date; shown at once by whoever holds the milestone. */
  onChange: (changes: MilestoneChanges) => void;
  onClose: () => void;
}) {
  const router = useRouter();
  const notes = useMilestoneNotes(projectId, milestone.id);

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
        <DialogTitle className="sr-only">{milestone.name}</DialogTitle>
        <DialogDescription className="sr-only">{m.detailDescription}</DialogDescription>
        {/* The scroll box is inside, not the dialog itself, so the editor's menus are not clipped. */}
        <div className="max-h-[88dvh] min-h-64 overflow-y-auto overscroll-contain rounded-xl">
          <MilestoneDetailBody projectId={projectId} milestone={milestone} canEdit={canEdit} onChange={onChange} notes={notes} />
        </div>
      </DialogContent>
    </Dialog>
  );
}

/** The milestone document's autosave; whoever shows the body holds it, to flush it on closing. */
export function useMilestoneNotes(projectId: string, milestoneId: string) {
  return useAutosave((content: RichDoc | null) => updateMilestone({ projectId, id: milestoneId, changes: { content } }));
}

/**
 * What a milestone shows, in the overview's dialog or in the panel beside a ticket (D-138): name,
 * progress, target date, the document, and its tickets. `stacked` puts the tickets under the
 * document instead of beside it, for a narrow panel. Without `onChange` and `notes` nothing in it
 * can be changed, whatever `canEdit` says (the panel beside a ticket, D-157).
 */
export function MilestoneDetailBody({
  projectId,
  milestone,
  canEdit: mayEdit,
  onChange,
  notes,
  stacked = false,
  issueLimit,
  openHref,
}: {
  projectId: string;
  milestone: Milestone;
  canEdit: boolean;
  onChange?: (changes: MilestoneChanges) => void;
  notes?: ReturnType<typeof useMilestoneNotes>;
  stacked?: boolean;
  /** At most this many tickets are listed; the rest are counted on one line. */
  issueLimit?: number;
  /** Where the milestone has its own place; a link to it stands right of the name. */
  openHref?: string;
}) {
  const canEdit = mayEdit && onChange !== undefined && notes !== undefined;
  const router = useRouter();
  const percent = milestoneProgress(milestone);
  const done = milestone.status === "COMPLETED";
  const nameInput = useRemoteValue<HTMLInputElement>(milestone.name, isFocused);
  // Comments on the blocks of the document, and the activity under it, are open to everyone who sees the milestone (D-165), also in the panel beside a ticket.
  const target = useMemo(() => ({ type: "milestone" as const, id: milestone.id }), [milestone.id]);
  const feed = useContentFeed(target, milestone);
  const members = useQuery({
    queryKey: ["project-members", projectId],
    queryFn: () => apiFetch<ProjectMember[]>(`/api/v1/projects/${encodeURIComponent(projectId)}/members`),
    staleTime: 60_000,
  });
  const mentionables = useMemo(() => mentionablesOf(members.data ?? []), [members.data]);
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
  // Sub-issues sit under their parents, one step in for every level, as in the issue lists. The
  // column is narrow, so the deeper ones are left out and counted on one line (D-142).
  const rows = useMemo(() => {
    const all = withoutDeepRows(arrangeBySubIssues(issues.data ?? []));
    return issueLimit === undefined ? all : upTo(all, issueLimit);
  }, [issues.data, issueLimit]);

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
    else if (name !== milestone.name) onChange?.({ name });
  };

  return (
        <div className={cn("flex flex-col", !stacked && "lg:flex-row")}>
          <div className="flex min-w-0 flex-1 flex-col gap-5 p-5 pt-6">
            <div className="flex flex-col gap-3">
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
                {openHref ? (
                  <Link
                    href={openHref}
                    aria-label={m.openInProject(milestone.name)}
                    title={m.openInProjectHint}
                    className="flex size-7 shrink-0 items-center justify-center rounded-md text-muted-foreground outline-none hover:bg-muted hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50"
                  >
                    <ArrowUpRightIcon className="size-4" aria-hidden="true" />
                  </Link>
                ) : null}
              </div>
              <div className="flex flex-wrap items-center gap-x-4 gap-y-2 text-[0.8125rem] leading-5">
                {canEdit ? (
                  <ChipSelect
                    label={m.status}
                    value={milestone.status}
                    options={projectStatusOptions}
                    onChange={(status) => onChange?.({ status })}
                  />
                ) : (
                  <span className="flex items-center gap-1.5">
                    <ProjectStatusIcon status={milestone.status} />
                    {projectStatusLabel(milestone.status)}
                  </span>
                )}
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
                    onChange={(targetDate) => onChange?.({ targetDate })}
                  />
                ) : (
                  <span className="text-muted-foreground">{m.noTarget}</span>
                )}
              </div>
            </div>
            <section className="flex flex-col gap-2">
              <div className="flex items-center justify-between gap-3">
                <h3 className="text-[0.8125rem] leading-5 font-medium text-muted-foreground">{m.notesTitle}</h3>
                {notes ? <SaveStatus status={notes.status} /> : null}
              </div>
              {canEdit || milestone.content ? (
                <RichTextEditor
                  // An empty document starts from the template so it is clear what to write (D-137).
                  initialContent={milestone.content ?? (canEdit ? milestoneTemplate(m.template) : null)}
                  editable={canEdit}
                  placeholder={m.notesPlaceholder}
                  ariaLabel={m.notesLabel}
                  className="min-h-48"
                  onChange={notes?.schedule}
                  keepLocal={notes?.isDirty}
                  blockIds
                  overlay={({ editor, container }) => (
                    <BlockComments
                      editor={editor}
                      container={container}
                      target={target}
                      threads={feed.data?.threads ?? []}
                      canComment={feed.data?.canComment ?? false}
                      mentionables={mentionables}
                    />
                  )}
                />
              ) : (
                <p className="text-muted-foreground">{m.noNotes}</p>
              )}
            </section>
            {/* What changed on the milestone and what was said about it, as on an issue. */}
            <ContentActivity target={target} watch={milestone} mentionables={mentionables} />
          </div>
          <aside
            aria-labelledby="milestone-issues-title"
            className={cn(
              "flex w-full shrink-0 flex-col gap-2 border-t p-5",
              !stacked && "lg:w-96 lg:border-t-0 lg:border-l lg:pt-6",
            )}
          >
            <div className={cn("flex items-center justify-between gap-2", !stacked && "lg:pr-8")}>
              <h3 id="milestone-issues-title" className="text-[0.8125rem] leading-5 font-medium text-muted-foreground">
                {m.issuesTitle}
                {issues.data ? <span className="ml-1.5 font-normal tabular-nums">{issues.data.length}</span> : null}
              </h3>
              <Link
                href={`/issues?project=${projectId}&milestone=${milestone.id}`}
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
                  row.kind === "hidden" ? (
                    // Only says how many are left out; it does nothing when pressed.
                    <li key={`hidden-${row.under}`} className="flex h-8 items-center gap-2 px-2 max-md:h-10">
                      <SubIssueIndent depth={row.depth} />
                      <span className="text-xs leading-4 text-muted-foreground tabular-nums">{m.hiddenSubIssues(row.count)}</span>
                    </li>
                  ) : row.kind === "context" ? (
                    // The parent is in another milestone (or none); it only gives its sub-issues their place.
                    <li key={`parent-${row.parent.number}`} className="flex h-9 items-center gap-2 rounded-md bg-muted/60 px-2 max-md:h-11">
                      <SubIssueIndent depth={row.depth} />
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
                      <span className="flex w-8 shrink-0 justify-center">
                        {row.issue.assignees.length > 0 ? <PeopleMarks people={row.issue.assignees} max={2} /> : null}
                      </span>
                    </li>
                  ),
                )}
              </ul>
            )}
          </aside>
        </div>
  );
}
