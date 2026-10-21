"use client";

import { useMemo } from "react";
import { BellIcon, BellOffIcon } from "lucide-react";
import type { MentionPerson } from "@/components/editor/mention";
import { Button } from "@/components/ui/button";
import { ActivityEvent, SubscriberAvatars, timelineOf } from "@/features/issues/components/issue-activity";
import { CommentComposer, CommentItem } from "@/features/issues/components/issue-comments";
import { apiErrorMessage } from "@/lib/api/error-message";
import { messages } from "@/messages";
import { contentCommentApi, reactToContentActivity } from "../api";
import { describeContent } from "../describe";
import { goToBlock } from "../go-to-block";
import type { ContentTarget } from "../types";
import { useContentFeed, useContentSubscription, useFeedRefresh } from "../use-content-feed";
import { ThreadCard } from "./thread-parts";

const m = messages.content;

/**
 * The activity of a project, a document or a milestone, the same as an issue's (D-165): its
 * changes, the comments under it and the threads on its blocks, mixed by time, oldest first, with a
 * box for a comment at the bottom. Open to everyone who can see the page; on a deleted document it
 * can only be read. A document also says who follows it, with a button to follow or stop (D-166).
 * `watch` is what the page was last rendered from (see `useContentFeed`).
 */
export function ContentActivity({
  target,
  watch,
  mentionables,
}: {
  target: ContentTarget;
  watch?: unknown;
  /** The people "@" offers in a comment. */
  mentionables: MentionPerson[];
}) {
  const feed = useContentFeed(target, watch);
  const refresh = useFeedRefresh(target);
  const follow = useContentSubscription(target);
  const api = useMemo(() => contentCommentApi(target, refresh), [target, refresh]);
  const data = feed.data;

  if (!data) {
    return feed.isError ? (
      <p role="alert" className="border-t pt-5 text-[0.8125rem] leading-5 text-destructive">
        {m.block.loadFailed}
      </p>
    ) : null;
  }

  const entries = timelineOf(data.items, data.comments, data.threads);
  // Nothing to read and nothing can be added (the example of the tour): no heading over nothing.
  if (entries.length === 0 && !data.canComment) return null;
  const subscription = data.subscription;

  return (
    <section data-presence-id="activity" aria-label={m.activity.heading} className="flex flex-col gap-3 border-t pt-5">
      <div className="flex flex-wrap items-center gap-2">
        <h3 className="mr-auto text-[0.9375rem] leading-[1.375rem] font-semibold">{m.activity.heading}</h3>
        {subscription && subscription.subscribers.length > 0 ? <SubscriberAvatars people={subscription.subscribers} /> : null}
        {subscription && !data.readOnly ? (
          <Button
            type="button"
            variant="ghost"
            size="sm"
            className="text-muted-foreground max-md:h-11"
            onClick={() => follow.mutate(!subscription.subscribed)}
          >
            {subscription.subscribed ? <BellOffIcon data-icon="inline-start" /> : <BellIcon data-icon="inline-start" />}
            {subscription.subscribed ? m.activity.unsubscribe : m.activity.subscribe}
          </Button>
        ) : null}
      </div>
      {follow.isError ? (
        <p role="alert" className="text-[0.8125rem] leading-5 text-destructive">
          {apiErrorMessage(follow.error)}
        </p>
      ) : null}
      <ol className="flex flex-col gap-2.5">
        {entries.map((entry) =>
          entry.kind === "comment" ? (
            <CommentItem key={entry.comment.id} api={api} comment={entry.comment} mentionables={mentionables} readOnly={data.readOnly} />
          ) : entry.kind === "thread" ? (
            <li key={entry.thread.id}>
              <ThreadCard
                target={target}
                thread={entry.thread}
                canComment={data.canComment}
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
              described={describeContent(entry.event)}
              react={async (emoji) => {
                const reactions = await reactToContentActivity({ id: entry.event.id, emoji });
                refresh();
                return reactions;
              }}
              readOnly={data.readOnly}
            />
          ),
        )}
      </ol>
      {data.canComment ? <CommentComposer api={api} mentionables={mentionables} /> : null}
    </section>
  );
}
