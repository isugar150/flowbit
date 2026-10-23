import { messages } from "@/messages";
import { apiFetch } from "@/lib/api/client";
import { personName } from "@/components/person";
import { activityText, type CardActivity } from "./card-activity";
import { describe } from "./components/activity-event";
import type { IssueActivityItem } from "./types";

const m = messages.issues.activity;

/** An entry of an issue's history as a line of a card, with the value it changed when it has one. */
export function cardActivityOf(item: IssueActivityItem): CardActivity {
  const { action, change } = describe(item);
  // A Git server's change for someone who has no account here is named as the server knows them.
  const via = item.detail.via;
  const who = item.actor ? personName(item.actor) : (via?.name ?? via?.provider ?? m.gitSource);
  return { who, text: activityText(action, change, true), at: item.createdAt };
}

/** The last entries of an issue's history, newest first, for the card over it on a calendar (D-201). */
export async function fetchRecentActivity(number: number, limit = 3): Promise<CardActivity[]> {
  const items = await apiFetch<IssueActivityItem[]>(`/api/v1/issues/${number}/activity/recent?limit=${limit}`);
  return items.map(cardActivityOf);
}
