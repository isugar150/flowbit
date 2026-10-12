import { CircleSlashIcon, UserIcon } from "lucide-react";
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";
import type { ProjectMember } from "@/features/projects/types";
import { initials } from "@/lib/initials";
import { messages } from "@/messages";
import type { ChipOption } from "./components/chip-select";
import {
  PresetIcon,
  PRESETS,
  PRIORITIES,
  PriorityIcon,
  STATUSES,
  StatusIcon,
  presetLabel,
  priorityLabel,
  statusLabel,
} from "./meta";
import type { IssuePreset, IssuePriority, IssueStatus } from "./types";

const m = messages.issues;

/** Stands for "nobody" / "no preset" in menus, which need a string value. */
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

export const presetOptions: ChipOption<IssuePreset | typeof NONE>[] = [
  { value: NONE, label: m.noPreset, icon: <CircleSlashIcon className="size-4 text-muted-foreground" aria-hidden /> },
  ...PRESETS.map((preset) => ({ value: preset, label: presetLabel(preset), icon: <PresetIcon preset={preset} /> })),
];

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
