"use client";

import {
  BoxIcon,
  CalendarCheckIcon,
  CalendarClockIcon,
  CalendarIcon,
  CalendarOffIcon,
  CircleDashedIcon,
  ListFilterIcon,
  SignalIcon,
  TagIcon,
  UsersIcon,
} from "lucide-react";
import {
  DropdownMenu,
  DropdownMenuCheckboxItem,
  DropdownMenuContent,
  DropdownMenuGroup,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuSub,
  DropdownMenuSubContent,
  DropdownMenuSubTrigger,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import type { ProjectMember, ProjectSummary } from "@/features/projects/types";
import { cn } from "@/lib/utils";
import { messages } from "@/messages";
import { NONE, assigneeOptions, priorityOptions, statusOptions } from "../options";
import type { Label } from "../types";
import { ChipSelect, chipClass, type ChipOption } from "./chip-select";
import { LabelFilterChip, labelFilterOptions } from "./label-chip";
import { projectOptions } from "./issue-properties";

const m = messages.issues;
const ICON = { className: "size-4 text-muted-foreground", "aria-hidden": true } as const;

/** The value of a filter that is off ("every …"). */
export const FILTER_ALL = "__all__";
export const FILTER_KEYS = ["project", "status", "priority", "assignee", "due", "label"] as const;
export type FilterKey = (typeof FILTER_KEYS)[number];
/** The filters with one value. Labels take several and are handled on their own. */
export type ChipKey = Exclude<FilterKey, "label">;
export const CHIP_KEYS = FILTER_KEYS.filter((key): key is ChipKey => key !== "label");

/** What the filters of a list are made of, and where they stand. */
export type IssueFilterProps = {
  /** The project whose issues these are; null on a list of issues of many projects. */
  projectId: string | null;
  projects: ProjectSummary[];
  members: ProjectMember[];
  labels: Label[];
  /** False where every issue is already the person's own ("내 티켓"): there is no assignee to pick. */
  assigneeFilter: boolean;
  /** The value of each filter with one value; `FILTER_ALL` when it is off. */
  values: Record<ChipKey, string>;
  /** The ticked labels (`NONE` stands for "no label"); none when the filter is off. */
  labelValue: string[];
  onChange: (key: ChipKey, value: string) => void;
  onLabelChange: (value: string[]) => void;
};

type FilterKind = { key: ChipKey; label: string; name: string; icon: React.ReactNode; options: ChipOption<string>[] };

/** The filters with one value, in the order of the menu and of the chips. */
function filterKindsOf({ projectId, projects, members, assigneeFilter }: IssueFilterProps): FilterKind[] {
  return [
    // Inside a project every issue has that project, so the filter is only on lists of many projects.
    ...(projectId === null
      ? [
          {
            key: "project" as const,
            label: m.projectFilter,
            name: m.filterNames.project,
            icon: <BoxIcon {...ICON} />,
            options: projectOptions(projects.filter((project) => project.myRole)),
          },
        ]
      : []),
    { key: "status", label: m.statusFilter, name: m.filterNames.status, icon: <CircleDashedIcon {...ICON} />, options: statusOptions },
    { key: "priority", label: m.priorityFilter, name: m.filterNames.priority, icon: <SignalIcon {...ICON} />, options: priorityOptions },
    ...(assigneeFilter
      ? [
          {
            key: "assignee" as const,
            label: m.assigneeFilter,
            name: m.filterNames.assignee,
            icon: <UsersIcon {...ICON} />,
            options: assigneeOptions(members),
          },
        ]
      : []),
    {
      key: "due",
      label: m.dueFilter,
      name: m.filterNames.due,
      icon: <CalendarIcon {...ICON} />,
      options: [
        { value: NONE, label: m.dueFilters.none, icon: <CalendarOffIcon {...ICON} /> },
        { value: "overdue", label: m.dueFilters.overdue, icon: <CalendarClockIcon {...ICON} /> },
        { value: "today", label: m.dueFilters.today, icon: <CalendarCheckIcon {...ICON} /> },
        { value: "week", label: m.dueFilters.week, icon: <CalendarIcon {...ICON} /> },
      ],
    },
  ];
}

/** The mark beside a kind of filter in the menu that tells it is on. */
function OnMark() {
  return (
    <>
      <span aria-hidden="true" className="size-1.5 shrink-0 rounded-full bg-primary" />
      <span className="sr-only">{m.filterOn}</span>
    </>
  );
}

/**
 * The one "필터" button of an issue list. It lists what the list can be filtered by; each opens its values beside it.
 * Only the filters that are on take room in the toolbar (as chips of their own, `ActiveFilterChips`), so the toolbar does
 * not grow with the number of kinds of filter (D-224).
 */
export function IssueFilterMenu(props: IssueFilterProps) {
  const { values, labelValue, onChange, onLabelChange, labels } = props;
  return (
    <DropdownMenu>
      <DropdownMenuTrigger
        render={
          <button
            type="button"
            aria-label={m.filter}
            className={cn(chipClass, "border-dashed text-muted-foreground hover:text-foreground aria-expanded:text-foreground")}
          />
        }
      >
        <ListFilterIcon className="size-3.5" aria-hidden="true" />
        <span>{m.filter}</span>
      </DropdownMenuTrigger>
      <DropdownMenuContent className="w-44">
        <DropdownMenuGroup>
          {filterKindsOf(props).map((kind) => (
            <DropdownMenuSub key={kind.key}>
              <DropdownMenuSubTrigger className="max-md:py-2">
                {kind.icon}
                <span className="truncate">{kind.name}</span>
                {values[kind.key] !== FILTER_ALL ? <OnMark /> : null}
              </DropdownMenuSubTrigger>
              <DropdownMenuSubContent className="max-h-72 min-w-44">
                <DropdownMenuRadioGroup value={values[kind.key]} onValueChange={(value) => onChange(kind.key, value)}>
                  {kind.options.map((option) => (
                    // Choosing is the point of opening it, so the whole menu closes (radio items stay open by default).
                    <DropdownMenuRadioItem key={option.value} value={option.value} closeOnClick>
                      {option.icon}
                      <span className="truncate">{option.label}</span>
                    </DropdownMenuRadioItem>
                  ))}
                </DropdownMenuRadioGroup>
              </DropdownMenuSubContent>
            </DropdownMenuSub>
          ))}
          <DropdownMenuSub>
            <DropdownMenuSubTrigger className="max-md:py-2">
              <TagIcon {...ICON} />
              <span className="truncate">{m.filterNames.label}</span>
              {labelValue.length > 0 ? <OnMark /> : null}
            </DropdownMenuSubTrigger>
            <DropdownMenuSubContent className="max-h-72 min-w-44">
              {/* Several labels can be ticked, so this one stays open until it is left. */}
              {labelFilterOptions(labels, NONE).map((option) => (
                <DropdownMenuCheckboxItem
                  key={option.id}
                  checked={labelValue.includes(option.id)}
                  onCheckedChange={(checked) =>
                    onLabelChange(checked ? [...labelValue, option.id] : labelValue.filter((id) => id !== option.id))
                  }
                >
                  {option.icon}
                  <span className="truncate">{option.label}</span>
                </DropdownMenuCheckboxItem>
              ))}
            </DropdownMenuSubContent>
          </DropdownMenuSub>
        </DropdownMenuGroup>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

/**
 * The filters that are on, one chip each: what it filters, the value (a menu to change it) and an "x" that turns it off.
 * Draws nothing when none is on.
 */
export function ActiveFilterChips(props: IssueFilterProps) {
  const { values, labelValue, onChange, onLabelChange, labels } = props;
  const active = filterKindsOf(props).filter((kind) => values[kind.key] !== FILTER_ALL);
  if (active.length === 0 && labelValue.length === 0) return null;
  return (
    <div role="group" aria-label={m.activeFilters} className="flex flex-wrap items-center gap-2">
      {active.map((kind) => (
        <ChipSelect
          key={kind.key}
          label={kind.label}
          prefix={kind.name}
          value={values[kind.key]}
          options={kind.options}
          onChange={(value) => onChange(kind.key, value)}
          onClear={() => onChange(kind.key, FILTER_ALL)}
          clearLabel={m.removeFilter(kind.name)}
        />
      ))}
      {/* Labels come after the assignee and the due date. */}
      {labelValue.length > 0 ? (
        <LabelFilterChip
          labels={labels}
          value={labelValue}
          onChange={onLabelChange}
          noneValue={NONE}
          prefix={m.filterNames.label}
          onClear={() => onLabelChange([])}
          clearLabel={m.removeFilter(m.filterNames.label)}
        />
      ) : null}
    </div>
  );
}
