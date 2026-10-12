import {
  BugIcon,
  CircleAlertIcon,
  CircleCheckIcon,
  CircleDashedIcon,
  CircleDotIcon,
  CircleIcon,
  CircleXIcon,
  LightbulbIcon,
  MinusIcon,
  SignalHighIcon,
  SignalLowIcon,
  SignalMediumIcon,
  WrenchIcon,
} from "lucide-react";
import type { RichDoc } from "@/components/editor/rich-text-editor";
import { cn } from "@/lib/utils";
import { messages } from "@/messages";
import type { IssuePreset, IssuePriority, IssueStatus } from "./types";

const m = messages.issues;

/** Order of the groups in a list: what is being worked on first, what is finished last. */
export const STATUS_ORDER: IssueStatus[] = ["IN_PROGRESS", "READY", "BACKLOG", "DONE", "CANCELED"];
/** Order in menus: the way an issue moves through the workflow. */
export const STATUSES: IssueStatus[] = ["BACKLOG", "READY", "IN_PROGRESS", "DONE", "CANCELED"];
export const PRIORITIES: IssuePriority[] = ["NONE", "URGENT", "HIGH", "MEDIUM", "LOW"];
export const PRESETS: IssuePreset[] = ["BUG", "ENHANCEMENT", "FEATURE"];

export const statusLabel = (status: IssueStatus) => m.statuses[status];
export const priorityLabel = (priority: IssuePriority) => m.priorities[priority];
export const presetLabel = (preset: IssuePreset) => m.presets[preset];

// Each state has its own shape as well as its own colour, so colour is never the only cue.

export function StatusIcon({ status, className }: { status: IssueStatus; className?: string }) {
  const props = { className: cn("size-4 shrink-0", className), "aria-hidden": true } as const;
  switch (status) {
    case "BACKLOG":
      return <CircleDashedIcon {...props} className={cn(props.className, "text-muted-foreground")} />;
    case "READY":
      return <CircleIcon {...props} className={cn(props.className, "text-muted-foreground")} />;
    case "IN_PROGRESS":
      return <CircleDotIcon {...props} className={cn(props.className, "text-warning")} />;
    case "DONE":
      return <CircleCheckIcon {...props} className={cn(props.className, "text-primary")} />;
    case "CANCELED":
      return <CircleXIcon {...props} className={cn(props.className, "text-muted-foreground")} />;
  }
}

export function PriorityIcon({ priority, className }: { priority: IssuePriority; className?: string }) {
  const props = { className: cn("size-4 shrink-0", className), "aria-hidden": true } as const;
  switch (priority) {
    case "NONE":
      return <MinusIcon {...props} className={cn(props.className, "text-muted-foreground")} />;
    case "URGENT":
      return <CircleAlertIcon {...props} className={cn(props.className, "text-urgent")} />;
    case "HIGH":
      return <SignalHighIcon {...props} />;
    case "MEDIUM":
      return <SignalMediumIcon {...props} />;
    case "LOW":
      return <SignalLowIcon {...props} />;
  }
}

export function PresetIcon({ preset, className }: { preset: IssuePreset; className?: string }) {
  const props = { className: cn("size-4 shrink-0 text-muted-foreground", className), "aria-hidden": true } as const;
  switch (preset) {
    case "BUG":
      return <BugIcon {...props} />;
    case "ENHANCEMENT":
      return <WrenchIcon {...props} />;
    case "FEATURE":
      return <LightbulbIcon {...props} />;
  }
}

/**
 * The outline a new issue's description starts with for each preset
 * (docs/features/M3-projects-and-issues.md, "프리셋"). Every preset ends with "완료 조건":
 * what has to be true for the work to count as done.
 */
export const PRESET_TEMPLATES: Record<IssuePreset, RichDoc> = {
  BUG: outline(["무슨 문제인가", "재현 방법", "기대한 결과", "실제 결과", "환경"]),
  ENHANCEMENT: outline(["배경", "현재 동작", "바꾸려는 동작", "영향 범위"]),
  FEATURE: outline(["왜 필요한가", "누가 쓰는가", "원하는 동작", "범위 밖"]),
};

/** An editor document with one heading and an empty paragraph per section, then a checklist. */
function outline(sections: string[]): RichDoc {
  const heading = (text: string) => ({ type: "heading", attrs: { level: 2 }, content: [{ type: "text", text }] });
  const emptyTask = { type: "taskItem", attrs: { checked: false }, content: [{ type: "paragraph" }] };
  return {
    type: "doc",
    content: [
      ...sections.flatMap((section) => [heading(section), { type: "paragraph" }]),
      heading("완료 조건"),
      { type: "taskList", content: [emptyTask] },
    ],
  };
}

/** Due dates within the year are shown without it: 10-24. */
export function formatDueDate(dueDate: string, today = new Date()): string {
  return dueDate.startsWith(`${today.getFullYear()}-`) ? dueDate.slice(5) : dueDate;
}
