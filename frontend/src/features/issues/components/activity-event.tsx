"use client";

import { TimeAgo } from "@/components/dates";
import { messages } from "@/messages";
import { describeCustomProperty } from "@/features/custom-properties/describe";
import { repositoryName } from "@/features/git/components/issue-git-links";
import { priorityLabel, statusLabel } from "../meta";
import { PersonAvatar, personName } from "@/components/person";
import type { IssueActivityItem, IssuePerson, IssuePriority, IssueStatus, Reaction } from "../types";
import { ReactionPicker, ReactionPills, useReactions } from "./issue-reactions";

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

/** What a line of the history says: who did what, and the value it is about. */
export type Described = { action: string; change?: string; href?: string; commits?: Commit[] };

/**
 * What happened, in words, with the old and new value where there is one. `href` makes the value
 * a link (to the Git server); `commits` are listed under the line.
 */
export function describe(item: IssueActivityItem): Described {
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
        action: m.status,
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
    case "LABELS_CHANGED":
      return { action: m.labels, change: addedRemoved(added, removed) };
    case "PROJECT_CHANGED":
      return { action: m.project, change: arrow(text(from), text(to)) };
    case "PARENT_CHANGED":
      return { action: m.parent, change: arrow(text(from), text(to)) };
    case "CUSTOM_PROPERTY_CHANGED":
      return describeCustomProperty(item.detail, m);
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
    case "GIT_UNLINKED":
      // Someone took a branch or pull request off the issue (D-161).
      return item.detail.kind === "PULL_REQUEST"
        ? { action: m.gitPrUnlinked, change: pullRequestName(item.detail, true), href: url }
        : { action: m.gitBranchUnlinked, change: text(item.detail.branch), href: url };
    case "GIT_COMMITS_PUSHED":
      return { action: m.gitCommitsPushed(Number(item.detail.count) || 0), commits: commitsOf(item.detail.commits) };
    default:
      // A kind of entry this version of the page does not know yet.
      return { action: m.changed };
  }
}

/**
 * One line of the history. Anyone who sees it can put an emoji on it: the chosen ones show under
 * the line, and the button to add one appears when the pointer is on the line (always on a touch
 * screen). `readOnly` (a deleted issue) only shows them.
 */
export function ActivityEvent({
  item,
  described,
  react,
  readOnly,
}: {
  item: { id: string; createdAt: string; actor: IssuePerson | null; detail: IssueActivityItem["detail"]; reactions: Reaction[] };
  described: Described;
  /** Puts the person's emoji on the line, or takes it back. */
  react: (emoji: string) => Promise<Reaction[]>;
  readOnly: boolean;
}) {
  const reactions = useReactions(item.reactions, react);
  const { change, href, commits } = described;
  // A Git server's change (D-153): a Git user without an account here is named as the server
  // knows them, and when the event named nobody the server itself is ("GitHub에서 …").
  const via = item.detail.via;
  const source = via ? (via.provider ?? m.gitSource) : null;
  const nobody = !item.actor && via != null && !via.name;
  const actor = item.actor ?? { loginId: (nobody ? source : via?.name) ?? m.gitSource, displayName: null, avatarUrl: null };
  const action = nobody ? m.fromServer(described.action) : described.action;
  return (
    <li className="group/entry flex items-start gap-2 text-[0.8125rem] leading-5 text-muted-foreground">
      <span className="flex h-5 shrink-0 items-center">
        <PersonAvatar person={actor} />
      </span>
      <div className="min-w-0 flex-1 break-words">
        <p>
          <span className="font-medium text-foreground" title={!item.actor && !nobody && source ? m.gitUnmatched(source) : undefined}>
            {personName(actor)}
          </span>
          {nobody ? "" : " "}
          {action}
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
          {source && !nobody ? (
            <>
              <span aria-hidden="true"> · </span>
              {source}
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
        {reactions.reactions.length > 0 ? (
          <div className="mt-1 flex flex-wrap items-center gap-1">
            <ReactionPills reactions={reactions.reactions} onToggle={reactions.toggle} readOnly={readOnly} small />
          </div>
        ) : null}
      </div>
      {readOnly ? null : (
        <ReactionPicker
          variant="icon"
          onPick={reactions.toggle}
          className="size-6 shrink-0 opacity-0 group-hover/entry:opacity-100 focus-visible:opacity-100 aria-expanded:opacity-100 [@media(hover:none)]:opacity-100"
        />
      )}
    </li>
  );
}
