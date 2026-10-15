import { CircleSlashIcon, DiamondIcon, UserIcon } from "lucide-react";
import { PersonAvatar, personName, type PersonLike } from "@/components/person";
import type { Milestone } from "@/features/milestones/types";
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

/** The project's milestones, plus "none". */
export function milestoneOptions(milestones: Pick<Milestone, "id" | "name">[]): ChipOption<string>[] {
  return [
    { value: NONE, label: m.noMilestone, icon: <CircleSlashIcon className="size-4 text-muted-foreground" aria-hidden /> },
    ...milestones.map((milestone) => ({
      value: milestone.id,
      label: milestone.name,
      icon: <DiamondIcon className="size-4 text-muted-foreground" aria-hidden />,
    })),
  ];
}
