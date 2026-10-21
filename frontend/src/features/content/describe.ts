import type { Described } from "@/features/issues/components/issue-activity";
import { priorityLabel } from "@/features/issues/meta";
import type { IssuePriority } from "@/features/issues/types";
import { healthLabel, projectStatusLabel } from "@/features/projects/meta";
import type { ProjectHealth, ProjectStatus } from "@/features/projects/types";
import { messages } from "@/messages";
import type { ContentActivityItem } from "./types";

const m = messages.content.activity;

const none = messages.issues.activity.none;
const text = (value: unknown): string => (typeof value === "string" && value ? value : none);
const names = (value: unknown): string[] => (Array.isArray(value) ? value.filter((item): item is string => typeof item === "string") : []);
const arrow = (before: string, after: string) => `${before} → ${after}`;
const role = (value: unknown) => (typeof value === "string" ? (messages.roles[value as keyof typeof messages.roles] ?? value) : none);

/** What a line of the activity of a project, a document or a milestone says, in words (D-165). */
export function describeContent(item: ContentActivityItem): Described {
  const { from, to, added, removed } = item.detail;
  switch (item.type) {
    case "NAME_CHANGED":
      return { action: m.nameChanged, change: arrow(text(from), text(to)) };
    case "CONTENT_EDITED":
      return { action: m.contentEdited };
    case "STATUS_CHANGED":
      return { action: m.status, change: arrow(projectStatusLabel(from as ProjectStatus), projectStatusLabel(to as ProjectStatus)) };
    case "PRIORITY_CHANGED":
      return { action: m.priority, change: arrow(priorityLabel(from as IssuePriority), priorityLabel(to as IssuePriority)) };
    case "LEAD_CHANGED":
      return { action: m.lead, change: arrow(text(from), text(to)) };
    case "DATES_CHANGED":
      return { action: m.dates, change: arrow(text(from), text(to)) };
    case "TARGET_DATE_CHANGED":
      return { action: m.targetDate, change: arrow(text(from), text(to)) };
    case "LABELS_CHANGED":
      return { action: m.labels, change: [...names(added).map((name) => `+${name}`), ...names(removed).map((name) => `−${name}`)].join(", ") };
    case "ICON_CHANGED":
      return { action: m.icon };
    case "ARCHIVED":
      return { action: m.archived };
    case "RESTORED":
      return { action: m.restored };
    case "MEMBER_ADDED":
      return { action: m.memberAdded, change: `${text(item.detail.name)} (${role(item.detail.role)})` };
    case "MEMBER_REMOVED":
      return { action: m.memberRemoved, change: text(item.detail.name) };
    case "MEMBER_ROLE_CHANGED":
      return { action: m.memberRole, change: `${text(item.detail.name)}: ${arrow(role(from), role(to))}` };
    case "UPDATE_POSTED": {
      const health = typeof item.detail.health === "string" ? healthLabel(item.detail.health as ProjectHealth) : "";
      const note = typeof item.detail.text === "string" ? item.detail.text : "";
      return { action: m.updatePosted, change: [health, note].filter(Boolean).join(" · ") || undefined };
    }
    case "MILESTONE_ADDED":
      return { action: m.milestoneAdded, change: text(item.detail.name) };
    case "MILESTONE_REMOVED":
      return { action: m.milestoneRemoved, change: text(item.detail.name) };
    case "DOCUMENT_ADDED":
      return { action: m.documentAdded, change: text(to) };
    case "DOCUMENT_REMOVED":
      return { action: m.documentRemoved, change: text(from) };
    case "DOCUMENT_RESTORED":
      return { action: m.documentRestored, change: text(to) };
    default:
      // A kind of line this version of the page does not know yet.
      return { action: m.changed };
  }
}
