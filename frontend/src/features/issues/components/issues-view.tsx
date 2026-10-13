"use client";

import { Fragment, memo, useCallback, useDeferredValue, useEffect, useMemo, useState } from "react";
import dynamic from "next/dynamic";
import Link from "next/link";
import {
  BoxIcon,
  CircleDashedIcon,
  DiamondIcon,
  FileTextIcon,
  PlusIcon,
  SearchIcon,
  SignalIcon,
  Trash2Icon,
  UsersIcon,
  XIcon,
} from "lucide-react";

import { Button } from "@/components/ui/button";
import { Empty, EmptyContent, EmptyDescription, EmptyHeader, EmptyMedia, EmptyTitle } from "@/components/ui/empty";
import { Kbd } from "@/components/ui/kbd";
import { FormAlert } from "@/features/auth/components/form-alert";
import type { Milestone } from "@/features/milestones/types";
import type { ProjectMember, ProjectSummary } from "@/features/projects/types";
import { apiErrorMessage } from "@/lib/api/error-message";
import { useOptimisticMutation } from "@/lib/use-refreshing-mutation";
import { cn } from "@/lib/utils";
import { messages } from "@/messages";
import { updateIssue } from "../api";
import { STATUS_ORDER, StatusIcon, statusLabel } from "../meta";
import { NONE, assigneeOptions, milestoneOptions, priorityOptions, statusOptions } from "../options";
import { arrangeBySubIssues } from "../sub-issue-tree";
import type { Issue, IssueChanges, IssueStatus, Label } from "../types";
import { ChipSelect, type ChipOption } from "./chip-select";
import type { IssueDates } from "./issue-calendar";
import { projectOptions } from "./issue-properties";
import { IssueAssignee, IssueDue, IssueLabels, IssueProjectName, IssueTitleLink, PriorityMark } from "./issue-row";
import { IssueViewSwitch, useIssueView, useListAddress, useReplaceListQuery } from "./issue-views";
import { LabelFilterChip } from "./label-chip";
import { SubIssueContextRow, SubIssueIndent, SubIssueProgress } from "./sub-issue-rows";

const m = messages.issues;

// The list is what nearly everyone comes for, so only the list is in the page's own script. The
// other two views arrive when they are chosen, and the new-issue dialog (which carries the whole
// document editor) when it is first opened, or a moment earlier when its button is pointed at.
const IssueMilestoneChart = dynamic(() => import("./issue-timeline").then((module) => module.IssueMilestoneChart));
const IssueCalendarView = dynamic(() => import("./issue-calendar").then((module) => module.IssueCalendarView));
const loadNewIssueDialog = () => import("./new-issue-dialog");
const NewIssueDialog = dynamic(() => loadNewIssueDialog().then((module) => module.NewIssueDialog), { ssr: false });
const preloadNewIssueDialog = () => void loadNewIssueDialog();

function isTyping(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false;
  return target.isContentEditable || ["INPUT", "TEXTAREA", "SELECT"].includes(target.tagName);
}

/** "Every …" in a filter; NONE is "nobody" / "no milestone" / "no label". */
const ALL = "__all__";
const FILTER_KEYS = ["project", "status", "priority", "assignee", "label", "milestone"] as const;
/** The filters with one value, drawn as a menu. Labels take several and have their own chip. */
type ChipKey = Exclude<FilterKey, "label">;
const CHIP_KEYS = FILTER_KEYS.filter((key): key is ChipKey => key !== "label");
type FilterKey = (typeof FILTER_KEYS)[number];
/** The title search in the address: `?q=`. */
const SEARCH_KEY = "q";
const ALL_ICON = { className: "size-4 text-muted-foreground", "aria-hidden": true } as const;
/** `?milestone=none`, `?assignee=none` in the address stand for NONE. */
const NONE_PARAM = "none";

/**
 * A list of issues (a project's, all of them, or one person's) grouped by status, with the
 * new-issue dialog and its `C` shortcut. It can also be shown as a timeline chart or a calendar.
 * The list can be narrowed by status, priority, assignee, label and milestone, and by words in
 * the title; what is chosen lives in the address.
 */
export function IssuesView({
  projectId,
  issues: savedIssues,
  members,
  milestones,
  labels,
  projects,
  canEdit,
  deleted = false,
  empty,
  defaultAssigneeId = null,
}: {
  /** True when `issues` are the deleted ones: the list is then read-only, there to look things up. */
  deleted?: boolean;
  /** Who a new issue made from this list is assigned to at first: the person whose issues these are. */
  defaultAssigneeId?: string | null;
  /** What to say when there are no issues at all, where the usual words do not fit ("내 티켓"). */
  empty?: { title: string; description: string };
  /** The project whose issues these are; null on the list of every issue. */
  projectId: string | null;
  /** For linking a new issue to a project, and for the project filter on the list of every issue. */
  projects: ProjectSummary[];
  issues: Issue[];
  members: ProjectMember[];
  milestones: Milestone[];
  /** Every label in the workspace. */
  labels: Label[];
  /** False for the view-only role: the list is read-only and nothing can be created. */
  canEdit: boolean;
}) {
  // A status picked on a line, or an issue dragged to another day, shows right away: the line
  // moves to its new group before the save has come back.
  const mutation = useOptimisticMutation(
    savedIssues,
    (current, { number, changes }: { number: number; changes: Pick<IssueChanges, "status" | "startDate" | "dueDate"> }) =>
      current.map((issue) => (issue.number === number ? { ...issue, ...changes } : issue)),
    updateIssue,
  );
  const allIssues = mutation.value;
  const [dialogOpen, setDialogOpen] = useState(false);
  // The dialog is not rendered (or downloaded) until it is first asked for; after that it stays.
  const [dialogUsed, setDialogUsed] = useState(false);
  // The filters live in the address (`?status=TODO&milestone=<id>&q=…`), so the links on the
  // project overview open the list already narrowed, and back/forward and sharing keep it.
  // An issue open in a dialog changes the browser's address, not the list's (useListAddress).
  const { pathname, searchParams } = useListAddress();
  const replaceListQuery = useReplaceListQuery();
  // A filter value from the address: "none" stands for NONE, anything unknown counts as no filter.
  const readFilter = (key: ChipKey, known: (value: string) => boolean): string => {
    const param = searchParams.get(key);
    return param === NONE_PARAM ? NONE : param && known(param) ? param : ALL;
  };
  const replaceQuery = (change: (params: URLSearchParams) => void) => {
    const params = new URLSearchParams(searchParams.toString());
    change(params);
    replaceListQuery(pathname, params);
  };
  const setFilter = (key: ChipKey, value: string) =>
    replaceQuery((params) => {
      if (value === ALL) params.delete(key);
      else params.set(key, value === NONE ? NONE_PARAM : value);
    });
  const filters = {
    project: readFilter("project", (value) => projects.some((project) => project.id === value)),
    status: readFilter("status", (value) => STATUS_ORDER.some((status) => status === value)),
    priority: readFilter("priority", (value) => priorityOptions.some((option) => option.value === value)),
    assignee: readFilter("assignee", (value) => members.some((member) => member.userId === value)),
    milestone: readFilter("milestone", (value) => milestones.some((milestone) => milestone.id === value)),
  };
  // Labels are the one filter with several values at once: "?label=<id>,<id>,none".
  const labelParam = searchParams.get("label") ?? "";
  const labelFilter = useMemo(
    () =>
      labelParam
        .split(",")
        .map((value) => (value === NONE_PARAM ? NONE : value))
        .filter((value) => value === NONE || labels.some((label) => label.id === value)),
    [labelParam, labels],
  );
  const setLabelFilter = (value: string[]) =>
    replaceQuery((params) => {
      if (value.length === 0) params.delete("label");
      else params.set("label", value.map((item) => (item === NONE ? NONE_PARAM : item)).join(","));
    });
  const milestoneFilter = filters.milestone;
  // Typed text is kept here and copied to the address: an input driven by the address alone would
  // lag a step behind and break Korean composition.
  const [search, setSearch] = useState(() => searchParams.get(SEARCH_KEY) ?? "");
  // The box shows each letter at once; a long list catches up with the typing a moment later.
  const needle = useDeferredValue(search).trim().toLowerCase();
  const filtered = needle !== "" || labelFilter.length > 0 || CHIP_KEYS.some((key) => filters[key] !== ALL);
  const { project, status, priority, assignee, milestone } = filters;
  // Worked out again only when the issues or what narrows them changes, not on every render
  // (a menu opening, someone else arriving on the page).
  const issues = useMemo(() => {
    const matches = (filter: string, value: string | null | undefined) => filter === ALL || (value ?? NONE) === filter;
    return allIssues.filter(
      (issue) =>
        matches(project, issue.project?.id) &&
        matches(status, issue.status) &&
        // "No priority" is a priority of its own (NONE), not a missing value.
        (priority === ALL || issue.priority === priority) &&
        matches(assignee, issue.assignee?.id) &&
        (labelFilter.length === 0 ||
          (issue.labels.length === 0
            ? labelFilter.includes(NONE)
            : issue.labels.some((label) => labelFilter.includes(label.id)))) &&
        matches(milestone, issue.milestone?.id) &&
        (needle === "" || issue.title.toLowerCase().includes(needle) || issue.key.toLowerCase().includes(needle)),
    );
  }, [allIssues, project, status, priority, assignee, milestone, labelFilter, needle]);
  const all = (label: string, icon: React.ReactNode): ChipOption<string> => ({ value: ALL, label, icon });
  const filterChips: { key: ChipKey; label: string; options: ChipOption<string>[] }[] = [
    // Inside a project every issue has that project, so the filter is only on the list of all issues.
    ...(projectId === null
      ? [
          {
            key: "project" as const,
            label: m.projectFilter,
            options: [
              all(m.allProjects, <BoxIcon {...ALL_ICON} />),
              ...projectOptions(projects.filter((project) => project.myRole)),
            ],
          },
        ]
      : []),
    {
      key: "status",
      label: m.statusFilter,
      options: [all(m.allStatuses, <CircleDashedIcon {...ALL_ICON} />), ...statusOptions],
    },
    {
      key: "priority",
      label: m.priorityFilter,
      options: [all(m.allPriorities, <SignalIcon {...ALL_ICON} />), ...priorityOptions],
    },
    {
      key: "assignee",
      label: m.assigneeFilter,
      options: [all(m.allAssignees, <UsersIcon {...ALL_ICON} />), ...assigneeOptions(members)],
    },
    ...(milestones.length > 0
      ? [
          {
            key: "milestone" as const,
            label: m.milestoneFilter,
            options: [all(m.allMilestones, <DiamondIcon {...ALL_ICON} />), ...milestoneOptions(milestones)],
          },
        ]
      : []),
  ];
  const labelChip = <LabelFilterChip labels={labels} value={labelFilter} onChange={setLabelFilter} noneValue={NONE} />;
  const [view, setView] = useIssueView();
  const { mutate } = mutation;
  const changeStatus = useCallback(
    (number: number, next: IssueStatus) => mutate({ number, changes: { status: next } }),
    [mutate],
  );
  // Deleted issues are only looked at: nothing is made or changed among them.
  const writable = canEdit && !deleted;
  // Dates a new issue starts with, when it is made from the calendar.
  const [dialogDates, setDialogDates] = useState<IssueDates | null>(null);
  const openDialog = (dates: IssueDates | null) => {
    setDialogDates(dates);
    setDialogUsed(true);
    setDialogOpen(true);
  };
  const deletedToggle = (
    <Button
      variant="ghost"
      size="sm"
      className={cn("max-md:h-11", deleted ? "bg-muted" : "text-muted-foreground")}
      aria-pressed={deleted}
      nativeButton={false}
      render={<Link href={deleted ? pathname : `${pathname}?deleted=1`} />}
    >
      <Trash2Icon data-icon="inline-start" />
      {deleted ? m.backToActive : m.showDeleted}
    </Button>
  );
  // An issue linked to a project the person can only view is read-only for them.
  const viewOnly = useMemo(
    () => new Set(projects.filter((item) => item.myRole === "VIEWER").map((item) => item.id)),
    [projects],
  );
  const mayEdit = useCallback((issue: Issue) => !issue.project || !viewOnly.has(issue.project.id), [viewOnly]);

  useEffect(() => {
    if (!writable) return;
    const onKeyDown = (event: KeyboardEvent) => {
      // `code` is the physical key, so this also works while a Korean input method is active.
      if (event.code !== "KeyC" || event.ctrlKey || event.metaKey || event.altKey || event.isComposing) return;
      if (isTyping(event.target) || document.querySelector("[role=dialog]")) return;
      event.preventDefault();
      setDialogDates(null);
      setDialogUsed(true);
      setDialogOpen(true);
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [writable]);

  const newIssueButton = writable ? (
    <Button
      className="max-md:h-11"
      onClick={() => openDialog(null)}
      onPointerEnter={preloadNewIssueDialog}
      onFocus={preloadNewIssueDialog}
    >
      <PlusIcon data-icon="inline-start" />
      {m.new}
      <Kbd className="max-md:hidden">C</Kbd>
    </Button>
  ) : null;

  const groups = useMemo(
    () =>
      STATUS_ORDER.map((group) => {
        const items = issues.filter((issue) => issue.status === group);
        return { status: group, count: items.length, rows: arrangeBySubIssues(items) };
      }).filter((group) => group.count > 0),
    [issues],
  );

  return (
    <>
      {allIssues.length === 0 && !deleted ? (
        <div className="flex flex-1 items-center justify-center p-6">
          <Empty>
            <EmptyHeader>
              <EmptyMedia variant="icon">
                <FileTextIcon />
              </EmptyMedia>
              <EmptyTitle>{empty?.title ?? m.emptyTitle}</EmptyTitle>
              <EmptyDescription>{empty?.description ?? (canEdit ? m.emptyDescription : m.emptyReadOnly)}</EmptyDescription>
            </EmptyHeader>
            <EmptyContent>
              {newIssueButton}
              {deletedToggle}
            </EmptyContent>
          </Empty>
        </div>
      ) : (
        <div className="flex flex-col">
          <div data-tour="issue-toolbar" className="flex flex-wrap items-center gap-2 px-4 pb-2">
            <div className="relative">
              <SearchIcon
                className="pointer-events-none absolute top-1/2 left-2.5 size-3.5 -translate-y-1/2 text-muted-foreground"
                aria-hidden="true"
              />
              <input
                type="search"
                value={search}
                placeholder={m.searchPlaceholder}
                aria-label={m.searchLabel}
                autoComplete="off"
                onChange={(event) => {
                  const value = event.target.value;
                  setSearch(value);
                  replaceQuery((params) => {
                    if (value) params.set(SEARCH_KEY, value);
                    else params.delete(SEARCH_KEY);
                  });
                }}
                className="h-7 w-44 rounded-full border bg-background pr-2.5 pl-7.5 text-[0.8125rem] leading-5 outline-none placeholder:text-muted-foreground focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 max-md:h-9"
              />
            </div>
            {filterChips.map((chip) => (
              <Fragment key={chip.key}>
                {/* Labels come after the assignee, before the milestone. */}
                {chip.key === "milestone" ? labelChip : null}
                <ChipSelect
                  label={chip.label}
                  value={filters[chip.key]}
                  options={chip.options}
                  onChange={(value) => setFilter(chip.key, value)}
                />
              </Fragment>
            ))}
            {filterChips.some((chip) => chip.key === "milestone") ? null : labelChip}
            {filtered ? (
              <Button
                variant="ghost"
                size="sm"
                className="text-muted-foreground max-md:h-11"
                onClick={() => {
                  setSearch("");
                  replaceQuery((params) => {
                    for (const key of [...FILTER_KEYS, SEARCH_KEY]) params.delete(key);
                  });
                }}
              >
                <XIcon data-icon="inline-start" />
                {m.clearFilter}
              </Button>
            ) : null}
            <span className="flex-1" />
            {deletedToggle}
            <IssueViewSwitch view={view} onChange={setView} />
            {newIssueButton}
          </div>
          {deleted ? (
            <p className="border-t bg-muted/50 px-5 py-2 text-[0.8125rem] leading-5 text-muted-foreground">{m.deletedNotice}</p>
          ) : null}
          {issues.length === 0 ? (
            <p className="border-t px-5 py-6 text-[0.8125rem] leading-5 text-muted-foreground">
              {allIssues.length === 0 ? m.noDeletedIssues : m.noIssuesInFilter}
            </p>
          ) : null}
          {mutation.isError ? (
            <div className="px-4 pb-2">
              <FormAlert>{apiErrorMessage(mutation.error)}</FormAlert>
            </div>
          ) : null}
          {view === "milestone" ? (
            <IssueMilestoneChart issues={issues} />
          ) : view === "calendar" ? (
            <IssueCalendarView
              issues={issues}
              canMove={mayEdit}
              onMove={writable ? (issue, dates) => mutation.mutate({ number: issue.number, changes: dates }) : undefined}
              onCreate={writable ? openDialog : undefined}
            />
          ) : (
            <>
              <IssueListHeader showProject={projectId === null} />
              {groups.map((group) => (
            <section key={group.status} aria-label={statusLabel(group.status)}>
              <h2 className="flex h-9 items-center gap-2 border-y bg-muted px-5 text-[0.8125rem] leading-5 font-medium">
                <StatusIcon status={group.status} />
                {statusLabel(group.status)}
                <span className="font-normal text-muted-foreground tabular-nums">{group.count}</span>
              </h2>
              <ul>
                {group.rows.map((row) =>
                  row.kind === "context" ? (
                    <SubIssueContextRow
                      key={`context-${row.parent.number}`}
                      parent={row.parent}
                      className="flex h-10 items-center gap-2 border-b pr-5 pl-12 max-md:h-12"
                    />
                  ) : (
                    <IssueListRow
                      key={row.issue.id}
                      issue={row.issue}
                      depth={row.depth}
                      editable={writable && mayEdit(row.issue)}
                      showProject={projectId === null}
                      onStatusChange={changeStatus}
                    />
                  ),
                )}
              </ul>
            </section>
              ))}
            </>
          )}
        </div>
      )}
      {writable && dialogUsed ? (
        <NewIssueDialog
          open={dialogOpen}
          onOpenChange={setDialogOpen}
          projects={projects}
          defaultProjectId={projectId}
          members={members}
          milestones={milestones}
          labels={labels}
          defaultMilestoneId={milestoneFilter === ALL || milestoneFilter === NONE ? null : milestoneFilter}
          defaultAssigneeId={defaultAssigneeId}
          defaultDates={dialogDates}
        />
      ) : null}
    </>
  );
}

/**
 * One line of the list. Kept from rendering again unless its own issue changed, so typing in the
 * search box or opening a menu does not redo every line of a long list.
 */
/**
 * The columns at the end of a line, each with a width of its own so that they sit under one
 * another and under the header. The narrower the screen, the fewer there are.
 */
const COLUMNS = {
  project: "hidden w-32 shrink-0 overflow-hidden xl:block",
  labels: "hidden w-44 shrink-0 overflow-hidden xl:flex",
  milestone: "hidden w-32 shrink-0 overflow-hidden lg:flex",
  due: "hidden w-16 shrink-0 sm:block",
};

/**
 * The names of the columns, laid out exactly as a line is. It stays under the page header (and
 * under the tabs, on pages that have them) while the list scrolls beneath it.
 */
function IssueListHeader({ showProject }: { showProject: boolean }) {
  return (
    <div
      aria-hidden="true"
      className="sticky top-11 z-20 flex h-8 items-center gap-2 bg-background pr-5 pl-3 text-xs leading-4 text-muted-foreground group-has-[[data-pill-tabs]]/main:top-20 max-md:group-has-[[data-pill-tabs]]/main:top-22"
    >
      <span className="w-7 shrink-0" />
      <span className="w-4 shrink-0" />
      <span className="w-16 shrink-0">{m.columns.key}</span>
      <span className="min-w-0 flex-1">{m.columns.title}</span>
      {showProject ? <span className={COLUMNS.project}>{m.project}</span> : null}
      <span className={COLUMNS.labels}>{m.labels}</span>
      <span className={COLUMNS.milestone}>{m.milestone}</span>
      <span className={COLUMNS.due}>{m.dueDate}</span>
      <span className="w-4.5 shrink-0 overflow-hidden whitespace-nowrap lg:w-28">
        <span className="max-lg:hidden">{m.assignee}</span>
      </span>
    </div>
  );
}

const IssueListRow = memo(function IssueListRow({
  issue,
  depth,
  editable,
  showProject,
  onStatusChange,
}: {
  issue: Issue;
  depth: number;
  /** Whether the status can be changed right in the list. */
  editable: boolean;
  /** On lists that span projects, each line says which project it is in. */
  showProject: boolean;
  onStatusChange: (number: number, status: IssueStatus) => void;
}) {
  return (
    <li
      data-presence-id={`issue-row:${issue.number}`}
      data-tour="issue-row"
      className="flex h-10 items-center gap-2 border-b pr-5 pl-3 hover:bg-muted max-md:h-12"
    >
      <SubIssueIndent depth={depth} />
      {editable ? (
        <ChipSelect
          iconOnly
          label={m.statusOf(issue.key)}
          value={issue.status}
          options={statusOptions}
          onChange={(status) => onStatusChange(issue.number, status)}
        />
      ) : (
        <span className="flex size-7 items-center justify-center">
          <StatusIcon status={issue.status} />
        </span>
      )}
      <PriorityMark priority={issue.priority} />
      <span className="w-16 shrink-0 truncate text-[0.8125rem] leading-5 text-muted-foreground tabular-nums">
        {issue.key}
      </span>
      <IssueTitleLink issue={issue} />
      <SubIssueProgress issue={issue} />
      {/* Every line has the same columns whether or not it has the value, so they line up down the list. */}
      {showProject ? (
        <span className={COLUMNS.project}>
          <IssueProjectName issue={issue} />
        </span>
      ) : null}
      <span className={COLUMNS.labels}>
        <IssueLabels issue={issue} max={2} />
      </span>
      <span className={cn(COLUMNS.milestone, "items-center gap-1 text-[0.8125rem] leading-5 text-muted-foreground")}>
        {issue.milestone ? (
          <>
            <DiamondIcon className="size-3.5 shrink-0" aria-hidden="true" />
            <span className="truncate">{issue.milestone.name}</span>
          </>
        ) : null}
      </span>
      <span className={COLUMNS.due}>
        <IssueDue issue={issue} />
      </span>
      <IssueAssignee issue={issue} />
    </li>
  );
});
