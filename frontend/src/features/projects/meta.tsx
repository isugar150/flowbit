import {
  ActivityIcon,
  CircleCheckIcon,
  CircleDashedIcon,
  CircleDotIcon,
  CircleIcon,
  CircleXIcon,
  OctagonAlertIcon,
  TriangleAlertIcon,
} from "lucide-react";
import type { ChipOption } from "@/features/issues/components/chip-select";
import { cn } from "@/lib/utils";
import { messages } from "@/messages";
import type { ProjectHealth, ProjectStatus } from "./types";

/** The way a project moves through its life, as offered in the status menu. */
export const PROJECT_STATUSES: ProjectStatus[] = ["BACKLOG", "PLANNED", "IN_PROGRESS", "COMPLETED", "CANCELED"];

export const projectStatusLabel = (status: ProjectStatus) => messages.projects.statuses[status];

// Each status has its own shape as well as its own colour, so colour is never the only cue.
export function ProjectStatusIcon({ status, className }: { status: ProjectStatus; className?: string }) {
  const props = { className: cn("size-4 shrink-0", className), "aria-hidden": true } as const;
  switch (status) {
    case "BACKLOG":
      return <CircleDashedIcon {...props} className={cn(props.className, "text-muted-foreground")} />;
    case "PLANNED":
      return <CircleIcon {...props} className={cn(props.className, "text-muted-foreground")} />;
    case "IN_PROGRESS":
      return <CircleDotIcon {...props} className={cn(props.className, "text-warning")} />;
    case "COMPLETED":
      return <CircleCheckIcon {...props} className={cn(props.className, "text-primary")} />;
    case "CANCELED":
      return <CircleXIcon {...props} className={cn(props.className, "text-muted-foreground")} />;
  }
}

export const projectStatusOptions: ChipOption<ProjectStatus>[] = PROJECT_STATUSES.map((status) => ({
  value: status,
  label: projectStatusLabel(status),
  icon: <ProjectStatusIcon status={status} />,
}));

export const PROJECT_HEALTHS: ProjectHealth[] = ["ON_TRACK", "AT_RISK", "OFF_TRACK"];

export const healthLabel = (health: ProjectHealth) => messages.projects.healths[health];

const HEALTH_COLOR: Record<ProjectHealth, string> = {
  ON_TRACK: "text-success",
  AT_RISK: "text-warning",
  OFF_TRACK: "text-destructive",
};

/** Like the status icons, each health has its own shape as well as its colour. */
export function HealthIcon({ health, className }: { health: ProjectHealth; className?: string }) {
  const props = { className: cn("size-4 shrink-0", HEALTH_COLOR[health], className), "aria-hidden": true } as const;
  switch (health) {
    case "ON_TRACK":
      return <ActivityIcon {...props} />;
    case "AT_RISK":
      return <TriangleAlertIcon {...props} />;
    case "OFF_TRACK":
      return <OctagonAlertIcon {...props} />;
  }
}

/** Icon and coloured label, for example "순조로움". */
export function HealthBadge({ health, className }: { health: ProjectHealth; className?: string }) {
  return (
    <span className={cn("inline-flex items-center gap-1.5 font-medium", HEALTH_COLOR[health], className)}>
      <HealthIcon health={health} />
      {healthLabel(health)}
    </span>
  );
}

export const healthOptions: ChipOption<ProjectHealth>[] = PROJECT_HEALTHS.map((health) => ({
  value: health,
  label: healthLabel(health),
  icon: <HealthIcon health={health} />,
}));

/**
 * "3분 전", "어제", "2주 전": how long ago, in the reader's words.
 * Reads the clock, so render it through `<TimeAgo>` (components/dates.tsx), never directly.
 */
export function timeAgo(iso: string, now = Date.now()): string {
  const seconds = Math.round((new Date(iso).getTime() - now) / 1000);
  const format = new Intl.RelativeTimeFormat("ko", { numeric: "auto" });
  const steps: [Intl.RelativeTimeFormatUnit, number][] = [
    ["year", 31_536_000],
    ["month", 2_592_000],
    ["week", 604_800],
    ["day", 86_400],
    ["hour", 3_600],
    ["minute", 60],
  ];
  for (const [unit, size] of steps) {
    if (Math.abs(seconds) >= size) return format.format(Math.round(seconds / size), unit);
  }
  return format.format(0, "second");
}
