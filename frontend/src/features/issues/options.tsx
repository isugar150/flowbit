import { GaugeIcon, MinusIcon, UserIcon } from "lucide-react";
import { PersonAvatar, personName, type PersonLike } from "@/components/person";
import type { ProjectMember } from "@/features/projects/types";
import { messages } from "@/messages";
import type { ChipOption } from "./components/chip-select";
import {
  PRIORITIES,
  PriorityIcon,
  STATUSES,
  StatusIcon,
  priorityLabel,
  statusLabel,
} from "./meta";
import type { IssuePriority, IssueStatus } from "./types";

const m = messages.issues;

/** Stands for "nobody" / "none" in menus, which need a string value. */
export const NONE = "__none__";

export const statusOptions: ChipOption<IssueStatus>[] = STATUSES.map((status) => ({
  value: status,
  label: statusLabel(status),
  icon: <StatusIcon status={status} />,
}));

export const priorityOptions: ChipOption<IssuePriority>[] = PRIORITIES.map((priority) => ({
  value: priority,
  label: priorityLabel(priority),
  icon: <PriorityIcon priority={priority} />,
}));

/** The estimates the menu offers (story points); an estimate set otherwise (through the API) is offered too while it is the value. */
export const ESTIMATE_STEPS = [0, 1, 2, 3, 5, 8, 13, 21];

export function estimateOptions(current: number | null): ChipOption<string>[] {
  const steps = current !== null && !ESTIMATE_STEPS.includes(current) ? [...ESTIMATE_STEPS, current].sort((a, b) => a - b) : ESTIMATE_STEPS;
  return [
    { value: NONE, label: m.noEstimate, icon: <MinusIcon className="size-4 text-muted-foreground" aria-hidden /> },
    ...steps.map((points) => ({ value: String(points), label: m.estimatePoints(points), icon: <GaugeIcon className="size-4 text-muted-foreground" aria-hidden /> })),
  ];
}

/** As many assignees as the server takes (IssueService.MAX_ASSIGNEES). */
export const MAX_ASSIGNEES = 10;

/** The assignees in a few words: the one name, or the first name and how many more. */
export function assigneeSummary(people: PersonLike[]): string {
  if (people.length === 0) return m.noAssignee;
  const first = personName(people[0]);
  return people.length === 1 ? first : m.assigneeSummary(first, people.length - 1);
}

/** Everyone in the project, plus "nobody". For the list filter, which picks one person. */
export function assigneeOptions(members: ProjectMember[]): ChipOption<string>[] {
  return [
    { value: NONE, label: m.noAssignee, icon: <UserIcon className="size-4 text-muted-foreground" aria-hidden /> },
    ...members.map((member) => ({
      value: member.userId,
      label: personName(member),
      icon: <PersonAvatar person={member} />,
    })),
  ];
}
