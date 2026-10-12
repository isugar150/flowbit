"use client";

import { BellIcon, BellOffIcon } from "lucide-react";
import { TimeAgo } from "@/components/dates";
import { Button } from "@/components/ui/button";
import { Spinner } from "@/components/ui/spinner";
import { apiErrorMessage } from "@/lib/api/error-message";
import { useRefreshingMutation } from "@/lib/use-refreshing-mutation";
import { messages } from "@/messages";
import { setIssueSubscribed } from "../api";
import { priorityLabel, statusLabel } from "../meta";
import { PersonAvatar } from "../options";
import type { IssueActivityFeed, IssueActivityItem, IssuePriority, IssueStatus } from "../types";

const m = messages.issues.activity;

const text = (value: unknown): string => (typeof value === "string" && value ? value : m.none);
const names = (value: unknown): string[] => (Array.isArray(value) ? value.filter((item) => typeof item === "string") : []);

/** What happened, in words, with the old and new value where there is one. */
function describe(item: IssueActivityItem): { action: string; change?: string } {
  const { from, to, added, removed } = item.detail;
  const arrow = (before: string, after: string) => `${before} → ${after}`;
  switch (item.type) {
    case "CREATED":
      return { action: m.created };
    case "TITLE_CHANGED":
      return { action: m.title, change: arrow(text(from), text(to)) };
    case "DESCRIPTION_CHANGED":
      return { action: m.description };
    case "STATUS_CHANGED":
      return { action: m.status, change: arrow(statusLabel(from as IssueStatus), statusLabel(to as IssueStatus)) };
    case "PRIORITY_CHANGED":
      return { action: m.priority, change: arrow(priorityLabel(from as IssuePriority), priorityLabel(to as IssuePriority)) };
    case "ASSIGNEE_CHANGED":
      return { action: m.assignee, change: arrow(text(from), text(to)) };
    case "DUE_DATE_CHANGED":
      return { action: m.dueDate, change: arrow(text(from), text(to)) };
    case "MILESTONE_CHANGED":
      return { action: m.milestone, change: arrow(text(from), text(to)) };
    case "LABELS_CHANGED":
      return {
        action: m.labels,
        change: [...names(added).map((name) => `+${name}`), ...names(removed).map((name) => `−${name}`)].join(", "),
      };
    case "PROJECT_CHANGED":
      return { action: m.project, change: arrow(text(from), text(to)) };
    case "PARENT_CHANGED":
      return { action: m.parent, change: arrow(text(from), text(to)) };
    case "ATTACHMENT_ADDED":
      return { action: m.attachmentAdded, change: text(to) };
    case "ATTACHMENT_REMOVED":
      return { action: m.attachmentRemoved, change: text(from) };
    case "DOCUMENT_ADDED":
      return { action: m.documentAdded, change: text(to) };
    case "DOCUMENT_REMOVED":
      return { action: m.documentRemoved, change: text(from) };
    case "DELETED":
      return { action: m.deleted };
    case "RESTORED":
      return { action: m.restored };
    default:
      // A kind of entry this version of the page does not know yet.
      return { action: m.changed };
  }
}

/**
 * The history of an issue, oldest first, with who follows it. The author and the assignee follow
 * automatically; anyone who can see the issue can follow or stop with the button.
 */
export function IssueActivity({ number, feed }: { number: number; feed: IssueActivityFeed }) {
  const mutation = useRefreshingMutation(setIssueSubscribed);

  return (
    <section data-presence-id="activity" aria-labelledby="issue-activity-title" className="flex flex-col gap-3 border-t pt-5">
      <div className="flex flex-wrap items-center gap-2">
        <h3 id="issue-activity-title" className="mr-auto text-[0.9375rem] leading-[1.375rem] font-semibold">
          {m.heading}
        </h3>
        {feed.subscribers.length > 0 ? (
          <span
            title={m.subscribers(feed.subscribers.map((person) => person.displayName ?? person.loginId).join(", "))}
            className="flex items-center -space-x-1"
          >
            {feed.subscribers.slice(0, 5).map((person) => (
              <PersonAvatar key={person.id} person={person} />
            ))}
            <span className="sr-only">
              {m.subscribers(feed.subscribers.map((person) => person.displayName ?? person.loginId).join(", "))}
            </span>
          </span>
        ) : null}
        <Button
          type="button"
          variant="ghost"
          size="sm"
          className="text-muted-foreground max-md:h-11"
          disabled={mutation.isPending}
          onClick={() => mutation.mutate({ number, subscribed: !feed.subscribed })}
        >
          {mutation.isPending ? (
            <Spinner data-icon="inline-start" aria-hidden="true" />
          ) : feed.subscribed ? (
            <BellOffIcon data-icon="inline-start" />
          ) : (
            <BellIcon data-icon="inline-start" />
          )}
          {feed.subscribed ? m.unsubscribe : m.subscribe}
        </Button>
      </div>
      {mutation.isError ? (
        <p role="alert" className="text-[0.8125rem] leading-5 text-destructive">
          {apiErrorMessage(mutation.error)}
        </p>
      ) : null}
      <ol className="flex flex-col gap-2.5">
        {feed.items.map((item) => {
          const { action, change } = describe(item);
          return (
            <li key={item.id} className="flex items-start gap-2 text-[0.8125rem] leading-5 text-muted-foreground">
              <span className="flex h-5 shrink-0 items-center">
                <PersonAvatar person={item.actor} />
              </span>
              <p className="min-w-0 flex-1 break-words">
                <span className="font-medium text-foreground">{item.actor.displayName ?? item.actor.loginId}</span> {action}
                {change ? <span className="text-foreground"> {change}</span> : null}
                <span aria-hidden="true"> · </span>
                <TimeAgo iso={item.createdAt} />
              </p>
            </li>
          );
        })}
      </ol>
    </section>
  );
}
