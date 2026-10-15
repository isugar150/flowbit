"use client";

import { BellIcon, BellOffIcon } from "lucide-react";
import { TimeAgo } from "@/components/dates";
import type { MentionPerson } from "@/components/editor/mention";
import { Button } from "@/components/ui/button";
import { apiErrorMessage } from "@/lib/api/error-message";
import { useOptimisticMutation } from "@/lib/use-refreshing-mutation";
import { messages } from "@/messages";
import { repositoryName } from "@/features/git/components/issue-git-links";
import { setIssueSubscribed } from "../api";
import { priorityLabel, statusLabel } from "../meta";
import { PersonAvatar, personName } from "@/components/person";
import type { IssueActivityFeed, IssueActivityItem, IssueComment, IssuePriority, IssueStatus } from "../types";
import { CommentComposer, CommentItem } from "./issue-comments";

const m = messages.issues.activity;

const text = (value: unknown): string => (typeof value === "string" && value ? value : m.none);
const names = (value: unknown): string[] => (Array.isArray(value) ? value.filter((item) => typeof item === "string") : []);

const addedRemoved = (added: unknown, removed: unknown): string =>
  [...names(added).map((name) => `+${name}`), ...names(removed).map((name) => `−${name}`)].join(", ");

type Commit = { sha: string; message: string; url: string };

const commitsOf = (value: unknown): Commit[] =>
  Array.isArray(value)
    ? value.filter((item): item is Commit => typeof item === "object" && item !== null && typeof (item as Commit).sha === "string")
    : [];

/** `web#34 로그인 오류 수정`: a pull request as the history names it. */
function pullRequestName(detail: IssueActivityItem["detail"], withTitle: boolean): string {
  const name = `${repositoryName(text(detail.repository))}#${String(detail.number ?? "")}`;
  return withTitle && typeof detail.title === "string" ? `${name} ${detail.title}` : name;
}

/**
 * What happened, in words, with the old and new value where there is one. `href` makes the value
 * a link (to the Git server); `commits` are listed under the line.
 */
function describe(item: IssueActivityItem): { action: string; change?: string; href?: string; commits?: Commit[] } {
  const { from, to, added, removed } = item.detail;
  const arrow = (before: string, after: string) => `${before} → ${after}`;
  const url = typeof item.detail.url === "string" && /^https?:\/\//.test(item.detail.url) ? item.detail.url : undefined;
  switch (item.type) {
    case "CREATED":
      return { action: m.created };
    case "TITLE_CHANGED":
      return { action: m.title, change: arrow(text(from), text(to)) };
    case "DESCRIPTION_CHANGED":
      return { action: m.description };
    case "STATUS_CHANGED":
      return {
        action: item.detail.via ? m.statusFromGit : m.status,
        change: arrow(statusLabel(from as IssueStatus), statusLabel(to as IssueStatus)),
      };
    case "PRIORITY_CHANGED":
      return { action: m.priority, change: arrow(priorityLabel(from as IssuePriority), priorityLabel(to as IssuePriority)) };
    case "ASSIGNEE_CHANGED":
      // Who was added and who was taken off (D-125); entries from before then hold one name each way.
      return "added" in item.detail || "removed" in item.detail
        ? { action: m.assignee, change: addedRemoved(added, removed) }
        : { action: m.assignee, change: arrow(text(from), text(to)) };
    case "DUE_DATE_CHANGED":
      return { action: m.dueDate, change: arrow(text(from), text(to)) };
    case "MILESTONE_CHANGED":
      return { action: m.milestone, change: arrow(text(from), text(to)) };
    case "LABELS_CHANGED":
      return { action: m.labels, change: addedRemoved(added, removed) };
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
    case "DOCUMENT_RESTORED":
      return { action: m.documentRestored, change: text(to) };
    case "DELETED":
      return { action: m.deleted };
    case "RESTORED":
      return { action: m.restored };
    case "GIT_BRANCH_LINKED":
      return { action: m.gitBranchLinked, change: text(item.detail.branch), href: url };
    case "GIT_PR_LINKED":
      return {
        action: m.gitPrLinked,
        change: pullRequestName(item.detail, true) + (item.detail.closes === false ? ` ${m.gitReference}` : ""),
        href: url,
      };
    case "GIT_PR_MERGED":
      return { action: m.gitPrMerged, change: pullRequestName(item.detail, false), href: url };
    case "GIT_PR_CLOSED":
      return { action: m.gitPrClosed, change: pullRequestName(item.detail, false), href: url };
    case "GIT_COMMITS_PUSHED":
      return { action: m.gitCommitsPushed(Number(item.detail.count) || 0), commits: commitsOf(item.detail.commits) };
    default:
      // A kind of entry this version of the page does not know yet.
      return { action: m.changed };
  }
}

type Entry = { at: string; event: IssueActivityItem; comment?: undefined } | { at: string; comment: IssueComment; event?: undefined };

/** History entries and comments as one list, oldest first. */
function entriesOf(feed: IssueActivityFeed): Entry[] {
  const entries: Entry[] = [
    ...feed.items.map((event) => ({ at: event.createdAt, event })),
    ...feed.comments.map((comment) => ({ at: comment.createdAt, comment })),
  ];
  // Timestamps are ISO strings in UTC, so they sort as text; ties keep the order above.
  return entries.sort((a, b) => (a.at < b.at ? -1 : a.at > b.at ? 1 : 0));
}

/**
 * The history of an issue and its comments, mixed by time, oldest first, with who follows it and,
 * for people who may edit the issue, where to write a comment. The author, the assignee, whoever
 * comments and whoever is tagged with "@" follow automatically; anyone who can see the issue can
 * follow or stop with the button.
 */
export function IssueActivity({
  number,
  feed,
  canComment,
  mentionables,
}: {
  number: number;
  feed: IssueActivityFeed;
  canComment: boolean;
  /** The people "@" offers in a comment. */
  mentionables: MentionPerson[];
}) {
  const mutation = useOptimisticMutation(
    feed.subscribed,
    (_, next: { number: number; subscribed: boolean }) => next.subscribed,
    setIssueSubscribed,
  );
  const subscribed = mutation.value;
  const subscriberNames = feed.subscribers.map(personName).join(", ");

  return (
    <section data-presence-id="activity" aria-labelledby="issue-activity-title" className="flex flex-col gap-3 border-t pt-5">
      <div className="flex flex-wrap items-center gap-2">
        <h3 id="issue-activity-title" className="mr-auto text-[0.9375rem] leading-[1.375rem] font-semibold">
          {m.heading}
        </h3>
        {feed.subscribers.length > 0 ? (
          <span
            title={m.subscribers(subscriberNames)}
            className="flex items-center -space-x-1"
          >
            {feed.subscribers.slice(0, 5).map((person) => (
              <PersonAvatar key={person.id} person={person} />
            ))}
            <span className="sr-only">
              {m.subscribers(subscriberNames)}
            </span>
          </span>
        ) : null}
        <Button
          type="button"
          variant="ghost"
          size="sm"
          className="text-muted-foreground max-md:h-11"
          onClick={() => mutation.mutate({ number, subscribed: !subscribed })}
        >
          {subscribed ? <BellOffIcon data-icon="inline-start" /> : <BellIcon data-icon="inline-start" />}
          {subscribed ? m.unsubscribe : m.subscribe}
        </Button>
      </div>
      {mutation.isError ? (
        <p role="alert" className="text-[0.8125rem] leading-5 text-destructive">
          {apiErrorMessage(mutation.error)}
        </p>
      ) : null}
      <ol className="flex flex-col gap-2.5">
        {entriesOf(feed).map((entry) => {
          if (entry.comment) return <CommentItem key={entry.comment.id} comment={entry.comment} mentionables={mentionables} />;
          const item = entry.event;
          const { action, change, href, commits } = describe(item);
          // A Git user without an account here is named as the Git server knows them.
          const actor = item.actor ?? { loginId: m.gitName(item.detail.via?.name ?? "Git"), displayName: null, avatarUrl: null };
          return (
            <li key={item.id} className="flex items-start gap-2 text-[0.8125rem] leading-5 text-muted-foreground">
              <span className="flex h-5 shrink-0 items-center">
                <PersonAvatar person={actor} />
              </span>
              <div className="min-w-0 flex-1 break-words">
                <p>
                  <span className="font-medium text-foreground">{personName(actor)}</span> {action}
                  {change ? (
                    <>
                      {" "}
                      {href ? (
                        <a href={href} target="_blank" rel="noreferrer" className="text-foreground underline-offset-2 hover:underline">
                          {change}
                        </a>
                      ) : (
                        <span className="text-foreground">{change}</span>
                      )}
                    </>
                  ) : null}
                  <span aria-hidden="true"> · </span>
                  <TimeAgo iso={item.createdAt} />
                </p>
                {commits && commits.length > 0 ? (
                  <details className="group mt-0.5">
                    <summary className="w-fit cursor-pointer list-none rounded-sm outline-none select-none hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50 [&::-webkit-details-marker]:hidden">
                      <span aria-hidden="true" className="mr-1 inline-block transition-transform group-open:rotate-90">
                        ›
                      </span>
                      {m.gitCommitsShow}
                    </summary>
                    <ul className="mt-1 flex flex-col gap-0.5 pl-3">
                      {commits.map((commit) => (
                        <li key={commit.sha} className="flex min-w-0 gap-2">
                          <a
                            href={commit.url}
                            target="_blank"
                            rel="noreferrer"
                            className="shrink-0 font-mono text-xs leading-5 underline-offset-2 hover:text-foreground hover:underline"
                          >
                            {commit.sha.slice(0, 7)}
                          </a>
                          <span className="min-w-0 truncate text-foreground">{commit.message}</span>
                        </li>
                      ))}
                    </ul>
                  </details>
                ) : null}
              </div>
            </li>
          );
        })}
      </ol>
      {canComment ? <CommentComposer number={number} mentionables={mentionables} /> : null}
    </section>
  );
}
