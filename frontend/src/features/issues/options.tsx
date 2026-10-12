import { CircleSlashIcon, DiamondIcon, UserIcon } from "lucide-react";
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";
import type { Milestone } from "@/features/milestones/types";
import type { ProjectMember } from "@/features/projects/types";
import { initials } from "@/lib/initials";
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

type PersonLike = { loginId: string; displayName: string | null; avatarUrl: string | null };

export function PersonAvatar({ person }: { person: PersonLike }) {
  const name = person.displayName ?? person.loginId;
  return (
    <Avatar size="sm" className="size-4.5">
      {person.avatarUrl ? <AvatarImage src={person.avatarUrl} alt="" /> : null}
      <AvatarFallback className="text-[0.5625rem]">{initials(name)}</AvatarFallback>
    </Avatar>
  );
}

/** Everyone in the project, plus "nobody". */
export function assigneeOptions(members: ProjectMember[]): ChipOption<string>[] {
  return [
    { value: NONE, label: m.noAssignee, icon: <UserIcon className="size-4 text-muted-foreground" aria-hidden /> },
    ...members.map((member) => ({
      value: member.userId,
      label: member.displayName ?? member.loginId,
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
