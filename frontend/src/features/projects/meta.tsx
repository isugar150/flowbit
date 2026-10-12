import { CircleCheckIcon, CircleDashedIcon, CircleDotIcon, CircleIcon, CircleXIcon } from "lucide-react";
import type { ChipOption } from "@/features/issues/components/chip-select";
import { cn } from "@/lib/utils";
import { messages } from "@/messages";
import type { ProjectStatus } from "./types";

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
