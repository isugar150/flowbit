"use client";

import { useMemo } from "react";
import { BellIcon, BellOffIcon } from "lucide-react";
import type { MentionPerson } from "@/components/editor/mention";
import { Button } from "@/components/ui/button";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { ActivityEvent } from "@/features/issues/components/activity-event";
import { ShowOlderButton, timelineOf, useCollapsedEntries } from "@/features/issues/components/activity-timeline";
import { SubscriberAvatars } from "@/features/issues/components/subscriber-avatars";
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

/** Follow or stop following the page; with a hint, it says over the button what following brings. */
function FollowButton({ subscribed, hint, onClick }: { subscribed: boolean; hint: string | null; onClick: () => void }) {
  const content = (
    <>
      {subscribed ? <BellOffIcon data-icon="inline-start" /> : <BellIcon data-icon="inline-start" />}
      {subscribed ? m.activity.unsubscribe : m.activity.subscribe}
    </>
  );
  const className = "text-muted-foreground max-md:h-11";
  if (!hint) {
    return (
      <Button type="button" variant="ghost" size="sm" className={className} onClick={onClick}>
        {content}
      </Button>
    );
  }
  return (
    <Tooltip>
      <TooltipTrigger render={<Button type="button" variant="ghost" size="sm" className={className} onClick={onClick} />}>
        {content}
      </TooltipTrigger>
      <TooltipContent>{hint}</TooltipContent>
    </Tooltip>
  );
}

/**
 * The activity of a project, a document or a milestone, the same as an issue's (D-165): its
 * changes, the comments under it (with their replies) and the threads on its blocks, mixed by time,
 * newest first with a box for a comment at the top (D-167). Open to everyone who can see the page; on a deleted document it
 * can only be read. A document and a project also say who follows them, with a button to follow or stop (D-166, D-176).
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
  // Hooks come before any return: the entries are those of the feed once it is there.
  const timeline = useCollapsedEntries(data ? timelineOf(data.items, data.comments, data.threads) : []);

  if (!data) {
    return feed.isError ? (
      <p role="alert" className="border-t pt-5 text-[0.8125rem] leading-5 text-destructive">
        {m.block.loadFailed}
      </p>
    ) : null;
  }

  // Nothing to read and nothing can be added (the example of the tour): no heading over nothing.
  if (timeline.shown.length === 0 && !data.canComment) return null;
  const subscription = data.subscription;

  return (
    <section data-presence-id="activity" aria-label={m.activity.heading} className="flex flex-col gap-3 border-t pt-5">
      <div className="flex flex-wrap items-center gap-2">
        <h3 className="mr-auto text-[0.9375rem] leading-[1.375rem] font-semibold">{m.activity.heading}</h3>
        {subscription && subscription.subscribers.length > 0 ? <SubscriberAvatars people={subscription.subscribers} /> : null}
        {subscription && !data.readOnly ? (
          <FollowButton
            subscribed={subscription.subscribed}
            // A project's follow reaches further than the page's own activity: all its issues' changes.
            hint={target.type === "project" ? messages.projects.subscribeHint : null}
            onClick={() => follow.mutate(!subscription.subscribed)}
          />
        ) : null}
      </div>
      {follow.isError ? (
        <p role="alert" className="text-[0.8125rem] leading-5 text-destructive">
          {apiErrorMessage(follow.error)}
        </p>
      ) : null}
      {/* The box for a comment is at the top, where the newest activity is (D-167). */}
      {data.canComment ? <CommentComposer api={api} mentionables={mentionables} /> : null}
      <ol className="flex flex-col gap-2.5">
        {timeline.shown.map((entry) =>
          entry.kind === "comment" ? (
            <CommentItem
              key={entry.comment.id}
              api={api}
              comment={entry.comment}
              replies={entry.replies}
              mentionables={mentionables}
              readOnly={data.readOnly}
              canReply={data.canComment}
            />
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
      <ShowOlderButton hidden={timeline.hidden} onClick={timeline.expand} />
    </section>
  );
}
