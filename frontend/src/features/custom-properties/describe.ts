import type { Described } from "@/features/issues/components/activity-event";
import { messages } from "@/messages";

/** The lines both kinds of activity (an issue's and a project's) say about a custom property. */
type Words = {
  customAdded: string;
  customChanged: (label: string) => string;
  customRenamed: string;
  customRemoved: string;
};

const none = messages.issues.activity.none;
const text = (value: unknown): string => (typeof value === "string" && value ? value : none);

/**
 * What an entry about a property people added (D-186) says: added with its value, changed from one
 * value to another, renamed, or deleted. `detail.op` tells which; the values are as a person reads them.
 */
export function describeCustomProperty(detail: Record<string, unknown>, words: Words): Described {
  const label = text(detail.label);
  switch (detail.op) {
    case "ADDED":
      return { action: words.customAdded, change: typeof detail.to === "string" && detail.to ? `${label} = ${detail.to}` : label };
    case "RENAMED":
      return { action: words.customRenamed, change: `${text(detail.from)} → ${text(detail.to)}` };
    case "REMOVED":
      return { action: words.customRemoved, change: label };
    default:
      return { action: words.customChanged(label), change: `${text(detail.from)} → ${text(detail.to)}` };
  }
}
