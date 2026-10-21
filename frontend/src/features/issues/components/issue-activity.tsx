"use client";

import { useMemo } from "react";
import { BellIcon, BellOffIcon } from "lucide-react";
import type { MentionPerson } from "@/components/editor/mention";
import { Button } from "@/components/ui/button";
import { apiErrorMessage } from "@/lib/api/error-message";
import { useOptimisticMutation } from "@/lib/use-refreshing-mutation";
import { messages } from "@/messages";
import { goToBlock } from "@/features/content/go-to-block";
import { ThreadCard } from "@/features/content/components/thread-parts";
import { useContentFeed } from "@/features/content/use-content-feed";
import { setIssueSubscribed, toggleActivityReaction } from "../api";
import { issueCommentApi } from "../comment-api";
import type { IssueActivityFeed } from "../types";
import { ActivityEvent, describe } from "./activity-event";
import { ShowOlderButton, timelineOf, useCollapsedEntries } from "./activity-timeline";
import { CommentComposer, CommentItem } from "./issue-comments";
import { SubscriberAvatars } from "./subscriber-avatars";

const m = messages.issues.activity;

/**
 * The history of an issue and its comments, mixed by time, newest first (the latest 15, the rest behind a
 * button), with who follows it and, for people who may edit the issue, where to write a comment, which is at the top. The author, the assignee, whoever
 * comments and whoever is tagged with "@" follow automatically; anyone who can see the issue can
 * follow or stop with the button.
 */
export function IssueActivity({
  number,
  issueId,
  feed,
  canComment,
  mentionables,
  readOnly = false,
}: {
  number: number;
  /** The issue's id: the comments on the blocks of its body are found by it (D-165). */
  issueId: string;
  feed: IssueActivityFeed;
  canComment: boolean;
  /** The people "@" offers in a comment. */
  mentionables: MentionPerson[];
  /** The issue is deleted: emoji are shown but not changed. */
  readOnly?: boolean;
}) {
  const mutation = useOptimisticMutation(
    feed.subscribed,
    (_, next: { number: number; subscribed: boolean }) => next.subscribed,
    setIssueSubscribed,
  );
  const subscribed = mutation.value;
  const api = useMemo(() => issueCommentApi(number), [number]);
  // The comments on the blocks of the body stand among the rest, where their first comment was written.
  const target = useMemo(() => ({ type: "issue" as const, id: issueId }), [issueId]);
  const threads = useContentFeed(target, feed).data?.threads ?? [];
  const timeline = useCollapsedEntries(timelineOf(feed.items, feed.comments, threads));

  return (
    <section data-presence-id="activity" aria-labelledby="issue-activity-title" className="flex flex-col gap-3 border-t pt-5">
      <div className="flex flex-wrap items-center gap-2">
        <h3 id="issue-activity-title" className="mr-auto text-[0.9375rem] leading-[1.375rem] font-semibold">
          {m.heading}
        </h3>
        {feed.subscribers.length > 0 ? <SubscriberAvatars people={feed.subscribers} /> : null}
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
      {/* The box for a comment is at the top, where the newest activity is (D-167). */}
      {canComment ? <CommentComposer api={api} mentionables={mentionables} /> : null}
      <ol className="flex flex-col gap-2.5">
        {timeline.shown.map((entry) =>
          entry.kind === "comment" ? (
            <CommentItem
              key={entry.comment.id}
              api={api}
              comment={entry.comment}
              replies={entry.replies}
              mentionables={mentionables}
              readOnly={readOnly}
              canReply={canComment}
            />
          ) : entry.kind === "thread" ? (
            <li key={entry.thread.id}>
              <ThreadCard
                target={target}
                thread={entry.thread}
                canComment={!readOnly}
                mentionables={mentionables}
                quote
                collapseResolved
                onGoToBlock={goToBlock}
              />
            </li>
          ) : (
            <ActivityEvent
              key={entry.event.id}
              item={entry.event}
              described={describe(entry.event)}
              react={(emoji) => toggleActivityReaction({ id: entry.event.id, emoji })}
              readOnly={readOnly}
            />
          ),
        )}
      </ol>
      <ShowOlderButton hidden={timeline.hidden} onClick={timeline.expand} />
    </section>
  );
}
