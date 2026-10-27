"use client";

import { useCallback, useDeferredValue, useEffect, useMemo, useState } from "react";
import dynamic from "next/dynamic";
import Link from "next/link";
import { FileTextIcon, PlusIcon, SearchIcon, Trash2Icon, XIcon, ArrowUpDownIcon, CircleCheckIcon, LayersIcon } from "lucide-react";

import { useShownAt, useToday } from "@/components/dates";
import { Button } from "@/components/ui/button";
import { Empty, EmptyContent, EmptyDescription, EmptyHeader, EmptyMedia, EmptyTitle } from "@/components/ui/empty";
import { Kbd } from "@/components/ui/kbd";
import { FormAlert } from "@/features/auth/components/form-alert";
import type { ProjectMember, ProjectSummary } from "@/features/projects/types";
import { apiErrorMessage } from "@/lib/api/error-message";
import { useOptimisticMutation } from "@/lib/use-refreshing-mutation";
import { cn } from "@/lib/utils";
import { isTyping } from "@/lib/is-typing";
import { messages } from "@/messages";
import { updateIssue } from "../api";
import { DUE_FILTERS, NO_DUE, matchesDue } from "../due-filter";
import { STATUS_ORDER } from "../meta";
import { NONE, priorityOptions } from "../options";
import { DEFAULT_ISSUE_GROUPING, ISSUE_GROUPS, groupIssues, type IssueGrouping } from "../grouping";
import { useRememberedListQuery } from "../list-memory";
import { useProgressiveCount, withinBudget } from "../progressive";
import { useStableList } from "../stable";
import { arrangeBySubIssues } from "../sub-issue-tree";
import type { Issue, IssueChanges, IssueStatus, Label } from "../types";
import { NEW_ISSUE_EVENT } from "./new-issue-launcher";
import { DEFAULT_ISSUE_SORT, ISSUE_SORTS, sortIssues, type IssueSort } from "../sort";
import { ChipSelect, type ChipOption } from "./chip-select";
import type { IssueDates } from "./issue-calendar";
import { IssueListHeader, IssueListRow } from "./issue-list-row";
import { ActiveFilterChips, CHIP_KEYS, FILTER_ALL, FILTER_KEYS, IssueFilterMenu, type ChipKey } from "./issue-filter-menu";
import { IssueViewSwitch, useIssueView, useListAddress, useReplaceListQuery } from "./issue-views";
import { SubIssueContextRow } from "./sub-issue-rows";

const m = messages.issues;

// The list is what nearly everyone comes for, so only the list is in the page's own script. The
// other two views arrive when they are chosen, and the new-issue dialog (which carries the whole
// document editor) when it is first opened, or a moment earlier when its button is pointed at.
const IssueTimeline = dynamic(() => import("./issue-timeline").then((module) => module.IssuesTimelineView));
const IssueCalendarView = dynamic(() => import("./issue-calendar").then((module) => module.IssuesCalendarView));
const loadNewIssueDialog = () => import("./new-issue-dialog");
const NewIssueDialog = dynamic(() => loadNewIssueDialog().then((module) => module.NewIssueDialog), { ssr: false });
const preloadNewIssueDialog = () => void loadNewIssueDialog();

/** "Every …" in a filter; NONE is "nobody" / "no label" / "no due date". */
const ALL = FILTER_ALL;
/** The order of the list in the address: `?sort=`. The default is left out. */
const SORT_KEY = "sort";
const SORT_OPTIONS: ChipOption<IssueSort>[] = ISSUE_SORTS.map((value) => ({
  value,
  label: m.sorts[value],
  icon: <ArrowUpDownIcon className="size-4 text-muted-foreground" aria-hidden />,
}));
/**
 * How long done and canceled issues stay in the list after they were finished: `?completed=`.
 * Two weeks is the default and is left out; older ones are counted under their group instead
 * (the user's call, 2026-10-15: a list that keeps every finished issue only grows).
 */
const COMPLETED_KEY = "completed";
const COMPLETED_WINDOWS = ["2w", "1m", "all", "none"] as const;
type CompletedWindow = (typeof COMPLETED_WINDOWS)[number];
const DEFAULT_COMPLETED: CompletedWindow = "2w";
const DAY_MS = 24 * 60 * 60 * 1000;
const COMPLETED_DAYS: Record<CompletedWindow, number | null> = { "2w": 14, "1m": 30, all: null, none: 0 };
const COMPLETED_ICON = <CircleCheckIcon className="size-4 text-muted-foreground" aria-hidden />;

/** Whether a finished issue is left out of the list for now: finished before the window began. */
function finishedLongAgo(issue: Issue, window: CompletedWindow, now: number): boolean {
  const days = COMPLETED_DAYS[window];
  if (days === null || (issue.status !== "DONE" && issue.status !== "CANCELED")) return false;
  if (days === 0) return true;
  return issue.completedAt !== null && now - Date.parse(issue.completedAt) > days * DAY_MS;
}

/** What the list is grouped by in the address: `?group=`. The default is left out. */
const GROUP_KEY = "group";
const GROUP_ICON = <LayersIcon className="size-4 text-muted-foreground" aria-hidden />;
/** The title search in the address: `?q=`. */
const SEARCH_KEY = "q";
/** `?assignee=none` in the address stands for NONE. */
const NONE_PARAM = "none";

/**
 * A list of issues (a project's, all of them, or one person's) grouped by status, with the
 * new-issue dialog and its `C` shortcut. It can also be shown as a timeline or a calendar.
 * The list can be narrowed by status, priority, assignee and label, and by words in
 * the title; what is chosen lives in the address.
 */
export function IssuesView({
  projectId,
  issues: savedIssues,
  members,
  labels,
  projects,
  canEdit,
  deleted = false,
  empty,
  underTabs = false,
  defaultAssigneeId = null,
  rememberFilters = true,
  assigneeFilter = true,
  openCreated = true,
  shareScope,
}: {
  /** Which of a person's issues these are ("내 티켓": the ones assigned to them or the ones they made): puts a "공유하기" button on the calendar (its month) and on the timeline (the months picked) (D-197, D-199). Left out where they are not shared. */
  shareScope?: "assigned" | "created";
  /** False for example data (the tour's) whose tickets have no page of their own: a ticket made in the dialog is then not opened. */
  openCreated?: boolean;
  /** False where every issue is already the person's own ("내 티켓"): there is no assignee to pick, and one remembered from another list is ignored. */
  assigneeFilter?: boolean;
  /** Whether the list takes part in the filters, order and grouping the three lists remember together. */
  rememberFilters?: boolean;
  /** True when `issues` are the deleted ones: the list is then read-only, there to look things up. */
  deleted?: boolean;
  /** Who a new issue made from this list is assigned to at first: the person whose issues these are. */
  defaultAssigneeId?: string | null;
  /**
   * True on pages with a row of tabs under the page header: the column names then stick below
   * the tabs. Told by the page, not read from the document: a page left a moment ago stays in
   * the document, hidden, and its tabs would be counted.
   */
  underTabs?: boolean;
  /** What to say when there are no issues at all, where the usual words do not fit ("내 티켓"). */
  empty?: { title: string; description: string };
  /** The project whose issues these are; null on the list of every issue. */
  projectId: string | null;
  /** For linking a new issue to a project, and for the project filter on the list of every issue. */
  projects: ProjectSummary[];
  issues: Issue[];
  members: ProjectMember[];
  /** Every label in the workspace. */
  labels: Label[];
  /** False for the view-only role: the list is read-only and nothing can be created. */
  canEdit: boolean;
}) {
  // A status picked on a line, or an issue dragged to another day, shows right away: the line
  // moves to its new group before the save has come back.
  // The page is read again after a save and when someone else changes something, and the server sends every issue as a new
  // object: the ones that did not change stay the objects they were, so only the lines that changed are drawn again (D-206).
  const stableIssues = useStableList(savedIssues, (issue) => issue.id);
  const mutation = useOptimisticMutation(
    stableIssues,
    (current, { number, changes }: { number: number; changes: Pick<IssueChanges, "status" | "startDate" | "dueDate"> }) =>
      current.map((issue) => (issue.number === number ? { ...issue, ...changes } : issue)),
    updateIssue,
  );
  const allIssues = mutation.value;
  const [dialogOpen, setDialogOpen] = useState(false);
  // The dialog is not rendered (or downloaded) until it is first asked for; after that it stays.
  const [dialogUsed, setDialogUsed] = useState(false);
  // The filters live in the address (`?status=TODO&project=<id>&q=…`), so the links on the
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
  // Each filter is a value of its own (not a field of one object made on every render), so that the list below can
  // name exactly what it depends on.
  const project = readFilter("project", (value) => projects.some((option) => option.id === value));
  const status = readFilter("status", (value) => STATUS_ORDER.some((known) => known === value));
  const priority = readFilter("priority", (value) => priorityOptions.some((option) => option.value === value));
  const assignee = assigneeFilter ? readFilter("assignee", (value) => members.some((member) => member.userId === value)) : ALL;
  const due = readFilter("due", (value) => DUE_FILTERS.some((known) => known === value));
  const filters = { project, status, priority, assignee, due };
  // The day, for the due-date filter; null until the browser takes over, and then the filter leaves nothing out.
  const today = useToday();
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
  // Typed text is kept here and copied to the address: an input driven by the address alone would
  // lag a step behind and break Korean composition.
  const [search, setSearch] = useState(() => searchParams.get(SEARCH_KEY) ?? "");
  // The box shows each letter at once; a long list catches up with the typing a moment later.
  const needle = useDeferredValue(search).trim().toLowerCase();
  const filtered = needle !== "" || labelFilter.length > 0 || CHIP_KEYS.some((key) => filters[key] !== ALL);
  // The list of issues (at most a thousand) narrowed by the filters. It is worked out on each render rather than
  // memoized by hand: that is under a millisecond, and the hand-written dependency list could not be kept by the
  // React Compiler lint (it reads `searchParams`, which it cannot prove is not changed later).
  const matches = (filter: string, value: string | null | undefined) => filter === ALL || (value ?? NONE) === filter;
  const passesFilters = (issue: Issue) =>
      matches(project, issue.project?.id) &&
      matches(status, issue.status) &&
      // "No priority" is a priority of its own (NONE), not a missing value.
      (priority === ALL || issue.priority === priority) &&
      // One person is picked: the issue passes when they are one of its assignees.
      (assignee === ALL ||
        (assignee === NONE ? issue.assignees.length === 0 : issue.assignees.some((person) => person.id === assignee))) &&
      (due === ALL || matchesDue(issue, due === NONE ? NO_DUE : due, today)) &&
      (labelFilter.length === 0 ||
        (issue.labels.length === 0 ? labelFilter.includes(NONE) : issue.labels.some((label) => labelFilter.includes(label.id)))) &&
      (needle === "" || issue.title.toLowerCase().includes(needle) || issue.key.toLowerCase().includes(needle));
  const issues = allIssues.filter(passesFilters);
  const clearFilters = () => {
    setSearch("");
    replaceQuery((params) => {
      for (const key of [...FILTER_KEYS, SEARCH_KEY]) params.delete(key);
    });
  };
  // A ticket just made must show up. The filters are remembered across visits and across the three lists, so one left over
  // from earlier could hide it: they are cleared then (the list says so).
  const [filtersCleared, setFiltersCleared] = useState(false);
  const showCreated = (issue: Issue) => {
    if (passesFilters(issue)) return;
    clearFilters();
    setFiltersCleared(true);
  };
  // What the "필터" menu and the chips of the filters that are on are made from.
  const filterProps = {
    projectId,
    projects,
    members,
    labels,
    assigneeFilter,
    values: filters,
    labelValue: labelFilter,
    onChange: setFilter,
    onLabelChange: setLabelFilter,
  };
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
    // The sidebar's "새 티켓" opens this list's dialog, so the new ticket starts in this project.
    const onNewIssue = (event: Event) => {
      event.preventDefault();
      setDialogDates(null);
      setDialogUsed(true);
      setDialogOpen(true);
    };
    window.addEventListener("keydown", onKeyDown);
    window.addEventListener(NEW_ISSUE_EVENT, onNewIssue);
    return () => {
      window.removeEventListener("keydown", onKeyDown);
      window.removeEventListener(NEW_ISSUE_EVENT, onNewIssue);
    };
  }, [writable]);

  // Only on an empty list, as its next step. Otherwise the one button is in the sidebar.
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

  // The order inside each status. Sub-issues stay under their parent, in the order they were made.
  const sortParam = searchParams.get(SORT_KEY);
  const sort = ISSUE_SORTS.find((value) => value === sortParam) ?? DEFAULT_ISSUE_SORT;
  const setSort = (value: IssueSort) =>
    replaceQuery((params) => {
      if (value === DEFAULT_ISSUE_SORT) params.delete(SORT_KEY);
      else params.set(SORT_KEY, value);
    });

  // How the list is split: by status unless something else was picked (`?group=`).
  const groupParam = searchParams.get(GROUP_KEY);
  const groupChoices = ISSUE_GROUPS.filter((value) => value !== "project" || projectId === null);
  const grouping = groupChoices.find((value) => value === groupParam) ?? DEFAULT_ISSUE_GROUPING;
  const setGrouping = (value: IssueGrouping) =>
    replaceQuery((params) => {
      if (value === DEFAULT_ISSUE_GROUPING) params.delete(GROUP_KEY);
      else params.set(GROUP_KEY, value);
    });
  const groupOptions: ChipOption<IssueGrouping>[] = groupChoices.map((value) => ({ value, label: m.groupings[value], icon: GROUP_ICON }));

  const completedParam = searchParams.get(COMPLETED_KEY);
  const completed = COMPLETED_WINDOWS.find((value) => value === completedParam) ?? DEFAULT_COMPLETED;
  const setCompleted = (value: CompletedWindow) =>
    replaceQuery((params) => {
      if (value === DEFAULT_COMPLETED) params.delete(COMPLETED_KEY);
      else params.set(COMPLETED_KEY, value);
    });
  const completedOptions: ChipOption<CompletedWindow>[] = COMPLETED_WINDOWS.map((value) => ({
    value,
    label: m.completedWindows[value],
    icon: COMPLETED_ICON,
  }));

  // "Long ago" is measured from when the list was opened, so lines do not drop out while it is looked at.
  // The server may not read the clock: until the browser takes over, nothing counts as long ago.
  const now = useShownAt();
  const groups = useMemo(() => {
    return groupIssues(issues, grouping, projects).map((group) => {
      // Issues finished long ago leave the lines but are still counted, so the group can offer them.
      const shown = group.issues.filter((issue) => (now === null || !finishedLongAgo(issue, completed, now)));
      const items = sortIssues(shown, sort);
      return {
        key: group.key,
        label: group.label,
        icon: group.icon,
        count: items.length,
        hidden: group.issues.length - shown.length,
        rows: arrangeBySubIssues(items),
      };
    });
  }, [issues, grouping, projects, sort, completed, now]);

  // The last filters, order and grouping are shared by the three lists and kept across visits.
  // A long list is drawn in steps: the first lines at once and the rest while the page can already be used (D-206).
  const totalLines = groups.reduce((sum, group) => sum + group.rows.length, 0);
  const linesDrawn = useProgressiveCount(totalLines, view === "list");
  const drawnGroups = withinBudget(groups, linesDrawn);

  useRememberedListQuery({
    query: searchParams.toString(),
    enabled: rememberFilters && !deleted,
    restore: (saved) => replaceQuery((params) => saved.forEach((value, key) => params.set(key, value))),
  });

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
          {/*
            Nothing scrolls sideways (D-224). The first line has two groups that move as wholes when they do not fit side by side:
            search and the "필터" menu on the left; the display options and the view switch on the right (and, when even those do not
            fit, they wrap among themselves). The filters that are on are chips on a line of their own under it, so they can be as
            many as there are kinds of filter.
          */}
          <div data-tour="issue-toolbar" className="flex flex-col gap-2 px-4 pb-2">
            <div className="flex flex-wrap items-center gap-x-2 gap-y-2">
              <div className="flex min-w-0 flex-wrap items-center gap-2">
                <div className="relative shrink-0">
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
                <IssueFilterMenu {...filterProps} />
                {filtered ? (
                  <Button variant="ghost" size="sm" className="text-muted-foreground max-md:h-11" onClick={clearFilters}>
                    <XIcon data-icon="inline-start" />
                    {m.clearFilter}
                  </Button>
                ) : null}
              </div>
              <div className="ml-auto flex flex-wrap items-center justify-end gap-2">
                {deletedToggle}
                {/* The timeline and the calendar have an order of their own (by project, by day). */}
                {view === "list" ? (
                  <>
                    <ChipSelect label={m.completedFilter} value={completed} options={completedOptions} onChange={setCompleted} />
                    <ChipSelect label={m.groupBy} value={grouping} options={groupOptions} onChange={setGrouping} />
                    <ChipSelect label={m.sort} value={sort} options={SORT_OPTIONS} onChange={setSort} />
                  </>
                ) : null}
                <IssueViewSwitch view={view} onChange={setView} />
              </div>
            </div>
            <ActiveFilterChips {...filterProps} />
          </div>
          {deleted ? (
            <p className="border-t bg-muted/50 px-5 py-2 text-[0.8125rem] leading-5 text-muted-foreground">{m.deletedNotice}</p>
          ) : null}
          {filtersCleared ? (
            <p role="status" className="border-t bg-muted/50 px-5 py-2 text-[0.8125rem] leading-5 text-muted-foreground">
              {m.filtersClearedForCreated}
            </p>
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
          {view === "timeline" ? (
            <IssueTimeline
              issues={issues}
              share={shareScope ? (range) => ({ kind: "MY_ISSUES_TIMELINE", params: { ...range, scope: shareScope } }) : undefined}
            />
          ) : view === "calendar" ? (
            <IssueCalendarView
              issues={issues}
              canMove={mayEdit}
              onMove={writable ? (issue, dates) => mutation.mutate({ number: issue.number, changes: dates }) : undefined}
              onCreate={writable ? openDialog : undefined}
              share={shareScope ? (month) => ({ kind: "MY_ISSUES_CALENDAR", params: { month, scope: shareScope } }) : undefined}
            />
          ) : (
            <>
              <IssueListHeader showProject={projectId === null} sticky={underTabs ? "top-20 max-md:top-22" : "top-11"} />
              {drawnGroups.map((group) => (
            <section key={group.key} aria-label={group.label}>
              <h2 className="flex h-9 items-center gap-2 border-y bg-muted px-5 text-[0.8125rem] leading-5 font-medium">
                {group.icon}
                <span className="truncate">{group.label}</span>
                <span className="font-normal text-muted-foreground tabular-nums">{group.count}</span>
              </h2>
              <ul>
                {group.drawn.map((row) =>
                  row.kind === "context" ? (
                    <SubIssueContextRow
                      key={`context-${row.parent.number}`}
                      parent={row.parent}
                      depth={row.depth}
                      className="flex h-10 items-center gap-2 border-b bg-muted/60 pr-5 pl-3 max-md:h-12"
                      // Where the status and priority are on an issue line.
                      lead={
                        <>
                          <span className="w-7 shrink-0" />
                          <span className="w-4 shrink-0" />
                        </>
                      }
                      keyClassName="w-16"
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
                {group.hidden > 0 ? (
                  <li className="flex h-10 items-center border-b pr-5 pl-12 max-md:h-12">
                    <button
                      type="button"
                      className="rounded-sm text-[0.8125rem] leading-5 text-muted-foreground outline-none hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50"
                      onClick={() => setCompleted("all")}
                    >
                      {m.moreCompleted(group.hidden)}
                    </button>
                  </li>
                ) : null}
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
          labels={labels}
          defaultAssigneeId={defaultAssigneeId}
          defaultDates={dialogDates}
          onCreated={showCreated}
          openCreated={openCreated}
        />
      ) : null}
    </>
  );
}
