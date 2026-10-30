"use client";

import { useCallback, useEffect } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { contentFeedKey, getContentFeed, setContentSubscribed } from "./api";
import type { ContentFeed, ContentTarget } from "./types";

/**
 * The threads on the blocks of a page and, but for an issue, its activity and comments (D-165).
 * Every part of a page that shows them asks for the same thing and shares the answer. `watch` is
 * what the server last rendered the page from: when it changes (someone else changed the page, and
 * it was read again), the feed is read again too.
 */
export function useContentFeed(target: ContentTarget, watch?: unknown) {
  const query = useQuery({ queryKey: contentFeedKey(target), queryFn: () => getContentFeed(target), staleTime: 0 });
  const refetch = query.refetch;
  useEffect(() => {
    // Not while a reading is already on its way (the first one, when the page opens).
    void refetch({ cancelRefetch: false });
  }, [watch, refetch]);
  return query;
}

/**
 * Follows a document or stops following it. The button changes at once; the list of who follows
 * is read again afterwards, and the old state comes back if the server refuses.
 */
export function useContentSubscription(target: ContentTarget) {
  const queryClient = useQueryClient();
  const { type, id } = target;
  return useMutation({
    mutationFn: (subscribed: boolean) => setContentSubscribed({ target: { type, id }, subscribed }),
    onMutate: async (subscribed) => {
      const key = contentFeedKey({ type, id });
      await queryClient.cancelQueries({ queryKey: key });
      const previous = queryClient.getQueryData<ContentFeed>(key);
      queryClient.setQueryData<ContentFeed>(key, (feed) =>
        feed?.subscription ? { ...feed, subscription: { ...feed.subscription, subscribed } } : feed,
      );
      return { previous };
    },
    onError: (_error, _subscribed, context) => {
      if (context?.previous) queryClient.setQueryData(contentFeedKey({ type, id }), context.previous);
    },
    onSettled: () => void queryClient.invalidateQueries({ queryKey: contentFeedKey({ type, id }) }),
  });
}

/** Reads the page's feed again: after something was added to it, changed or taken away. */
export function useFeedRefresh(target: ContentTarget): () => Promise<void> {
  const queryClient = useQueryClient();
  const { type, id } = target;
  return useCallback(() => queryClient.invalidateQueries({ queryKey: contentFeedKey({ type, id }) }), [queryClient, type, id]);
}
