"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { usePathname, useSearchParams } from "next/navigation";
import { BoxIcon, CircleDashedIcon, DiamondIcon, FileTextIcon, PlusIcon, SearchIcon, SignalIcon, TagIcon, UsersIcon, XIcon } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Empty, EmptyContent, EmptyDescription, EmptyHeader, EmptyMedia, EmptyTitle } from "@/components/ui/empty";
import { Kbd } from "@/components/ui/kbd";
import { FormAlert } from "@/features/auth/components/form-alert";
import type { Milestone } from "@/features/milestones/types";
import type { ProjectMember, ProjectSummary } from "@/features/projects/types";
import { apiErrorMessage } from "@/lib/api/error-message";
import { useRefreshingMutation } from "@/lib/use-refreshing-mutation";
import { messages } from "@/messages";
import { updateIssue } from "../api";
import { PriorityIcon, STATUS_ORDER, StatusIcon, priorityLabel, statusLabel } from "../meta";
import { NONE, PersonAvatar, assigneeOptions, milestoneOptions, priorityOptions, statusOptions } from "../options";
import type { Issue, Label } from "../types";
import { ChipSelect, type ChipOption } from "./chip-select";
import { projectOptions } from "./issue-properties";
import { LabelDot, LabelPill } from "./label-chip";
import { NewIssueDialog } from "./new-issue-dialog";
import { DueDate } from "@/components/dates";

const m = messages.issues;

function isTyping(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false;
  return target.isContentEditable || ["INPUT", "TEXTAREA", "SELECT"].includes(target.tagName);
}

/** "Every …" in a filter; NONE is "nobody" / "no milestone" / "no preset". */
const ALL = "__all__";
const FILTER_KEYS = ["project", "status", "priority", "assignee", "label", "milestone"] as const;
type FilterKey = (typeof FILTER_KEYS)[number];
/** The title search in the address: `?q=`. */
const SEARCH_KEY = "q";
const ALL_ICON = { className: "size-4 text-muted-foreground", "aria-hidden": true } as const;
/** `?milestone=none`, `?assignee=none` in the address stand for NONE. */
const NONE_PARAM = "none";

/**
 * The issues of one project, grouped by status, with the new-issue dialog and its `C` shortcut.
 * The list can be narrowed by status, priority, assignee, label and milestone, and by words in
 * the title; what is chosen lives in the address.
 */
export function IssuesView({
  projectKey,
  issues: allIssues,
  members,
  milestones,
  labels,
  projects,
  canEdit,
}: {
  /** The project whose issues these are; null on the list of every issue. */
  projectKey: string | null;
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
  const [dialogOpen, setDialogOpen] = useState(false);
  // The filters live in the address (`?status=TODO&milestone=<id>&q=…`), so the links on the
  // project overview open the list already narrowed, and back/forward and sharing keep it.
  const pathname = usePathname();
  const searchParams = useSearchParams();
  // A filter value from the address: "none" stands for NONE, anything unknown counts as no filter.
  const readFilter = (key: FilterKey, known: (value: string) => boolean): string => {
    const param = searchParams.get(key);
    return param === NONE_PARAM ? NONE : param && known(param) ? param : ALL;
  };
  const replaceQuery = (change: (params: URLSearchParams) => void) => {
    const params = new URLSearchParams(searchParams.toString());
    change(params);
    const query = params.toString();
    // The list is already loaded; only the address changes, without asking the server again.
    window.history.replaceState(null, "", query ? `${pathname}?${query}` : pathname);
  };
  const setFilter = (key: FilterKey, value: string) =>
    replaceQuery((params) => {
      if (value === ALL) params.delete(key);
      else params.set(key, value === NONE ? NONE_PARAM : value);
    });
  const filters = {
    project: readFilter("project", (value) => projects.some((project) => project.key === value)),
    status: readFilter("status", (value) => STATUS_ORDER.some((status) => status === value)),
    priority: readFilter("priority", (value) => priorityOptions.some((option) => option.value === value)),
    assignee: readFilter("assignee", (value) => members.some((member) => member.userId === value)),
    label: readFilter("label", (value) => labels.some((label) => label.id === value)),
    milestone: readFilter("milestone", (value) => milestones.some((milestone) => milestone.id === value)),
  };
  const milestoneFilter = filters.milestone;
  // Typed text is kept here and copied to the address: an input driven by the address alone would
  // lag a step behind and break Korean composition.
  const [search, setSearch] = useState(() => searchParams.get(SEARCH_KEY) ?? "");
  const needle = search.trim().toLowerCase();
  const filtered = needle !== "" || FILTER_KEYS.some((key) => filters[key] !== ALL);
  const matches = (filter: string, value: string | null | undefined) => filter === ALL || (value ?? NONE) === filter;
  const issues = allIssues.filter(
    (issue) =>
      matches(filters.project, issue.project?.key) &&
      matches(filters.status, issue.status) &&
      // "No priority" is a priority of its own (NONE), not a missing value.
      (filters.priority === ALL || issue.priority === filters.priority) &&
      matches(filters.assignee, issue.assignee?.id) &&
      (filters.label === ALL ||
        (filters.label === NONE ? issue.labels.length === 0 : issue.labels.some((label) => label.id === filters.label))) &&
      matches(filters.milestone, issue.milestone?.id) &&
      (needle === "" || issue.title.toLowerCase().includes(needle) || issue.key.toLowerCase().includes(needle)),
  );
  const all = (label: string, icon: React.ReactNode): ChipOption<string> => ({ value: ALL, label, icon });
  const filterChips: { key: FilterKey; label: string; options: ChipOption<string>[] }[] = [
    // Inside a project every issue has that project, so the filter is only on the list of all issues.
    ...(projectKey === null
      ? [
          {
            key: "project" as const,
            label: m.projectFilter,
            options: [all(m.allProjects, <BoxIcon {...ALL_ICON} />), ...projectOptions(projects.filter((project) => project.myRole))],
          },
        ]
      : []),
    { key: "status", label: m.statusFilter, options: [all(m.allStatuses, <CircleDashedIcon {...ALL_ICON} />), ...statusOptions] },
    { key: "priority", label: m.priorityFilter, options: [all(m.allPriorities, <SignalIcon {...ALL_ICON} />), ...priorityOptions] },
    { key: "assignee", label: m.assigneeFilter, options: [all(m.allAssignees, <UsersIcon {...ALL_ICON} />), ...assigneeOptions(members)] },
    {
      key: "label",
      label: m.labelFilter,
      options: [
        all(m.allLabels, <TagIcon {...ALL_ICON} />),
        { value: NONE, label: m.noLabels, icon: <TagIcon {...ALL_ICON} /> },
        ...labels.map((label) => ({ value: label.id, label: label.name, icon: <LabelDot color={label.color} /> })),
      ],
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
  const mutation = useRefreshingMutation(updateIssue);
  // An issue linked to a project the person can only view is read-only for them.
  const mayEdit = (issue: Issue) =>
    !issue.project || projects.find((project) => project.key === issue.project?.key)?.myRole !== "VIEWER";

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
      {allIssues.length === 0 ? (
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
          <div className="flex flex-wrap items-center gap-2 px-4 pb-2">
            <div className="relative">
              <SearchIcon className="pointer-events-none absolute top-1/2 left-2.5 size-3.5 -translate-y-1/2 text-muted-foreground" aria-hidden="true" />
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
              <ChipSelect
                key={chip.key}
                label={chip.label}
                value={filters[chip.key]}
                options={chip.options}
                onChange={(value) => setFilter(chip.key, value)}
              />
            ))}
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
            {newIssueButton}
          </div>
          {issues.length === 0 ? (
            <p className="border-t px-5 py-6 text-[0.8125rem] leading-5 text-muted-foreground">{m.noIssuesInFilter}</p>
          ) : null}
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
                    {canEdit && mayEdit(issue) ? (
                      <ChipSelect
                        iconOnly
                        label={m.statusOf(issue.key)}
                        value={issue.status}
                        options={statusOptions}
                        disabled={mutation.isPending}
                        onChange={(status) =>
                          mutation.mutate({ number: issue.number, changes: { status } })
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
                      href={`/issue/${issue.key}`}
                      className="min-w-0 flex-1 truncate rounded-sm font-medium outline-none focus-visible:ring-3 focus-visible:ring-ring/50"
                    >
                      {issue.title}
                    </Link>
                    {projectKey === null && issue.project ? (
                      <span className="hidden max-w-40 shrink-0 truncate text-[0.8125rem] leading-5 text-muted-foreground lg:block">
                        {issue.project.name}
                      </span>
                    ) : null}
                    {issue.labels.length > 0 ? (
                      <span className="hidden shrink-0 items-center gap-1 sm:flex">
                        {issue.labels.slice(0, 3).map((label) => (
                          <LabelPill key={label.id} label={label} />
                        ))}
                      </span>
                    ) : null}
                    {issue.milestone ? (
                      <span className="hidden max-w-40 shrink-0 items-center gap-1 text-[0.8125rem] leading-5 text-muted-foreground md:flex">
                        <DiamondIcon className="size-3.5 shrink-0" aria-hidden="true" />
                        <span className="truncate">{issue.milestone.name}</span>
                      </span>
                    ) : null}
                    {issue.dueDate ? (
                      <span className="hidden shrink-0 text-[0.8125rem] leading-5 text-muted-foreground tabular-nums sm:block">
                        <DueDate value={issue.dueDate} />
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
        <NewIssueDialog
          open={dialogOpen}
          onOpenChange={setDialogOpen}
          projects={projects}
          defaultProjectKey={projectKey}
          members={members}
          milestones={milestones}
          labels={labels}
          defaultMilestoneId={milestoneFilter === ALL || milestoneFilter === NONE ? null : milestoneFilter}
        />
      ) : null}
    </>
  );
}
