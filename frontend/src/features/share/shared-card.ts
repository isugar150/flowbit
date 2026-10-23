import { activityText, type CardActivity } from "@/features/issues/card-activity";
import { describe } from "@/features/issues/components/activity-event";
import type { IssueCardData } from "@/features/issues/components/issue-card";
import type { IssueActivityType } from "@/features/issues/types";
import { messages } from "@/messages";
import type { SharedActivity, SharedCalendarIssue } from "./types";

/**
 * A line of the card over an issue on a shared calendar (D-201): who did what and when. The server tells the kind of change
 * and who made it, never the old or new value, so the line stops at what was done.
 */
export function sharedCardActivity(entry: SharedActivity): CardActivity {
  const type: IssueActivityType = entry.type;
  // What the history would say about a property with no property to name.
  const action =
    type === "CUSTOM_PROPERTY_CHANGED"
      ? messages.share.page.activityProperty
      : describe({ id: "", type, detail: {}, actor: null, createdAt: entry.at, reactions: [] }).action;
  return { who: entry.actor, text: activityText(action, undefined, entry.actor !== null), at: entry.at };
}

/** The card over an issue on a shared calendar. */
export function sharedCardOf(issue: SharedCalendarIssue): IssueCardData {
  return {
    key: issue.key,
    title: issue.title,
    status: issue.status,
    priority: issue.priority,
    startDate: issue.startDate,
    dueDate: issue.dueDate,
    project: issue.project,
    requester: issue.createdBy,
    labels: issue.labels,
    activity: issue.recentActivity.map(sharedCardActivity),
  };
}
