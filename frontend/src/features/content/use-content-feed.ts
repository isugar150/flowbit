"use client";

import { useCallback, useEffect } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { contentFeedKey, getContentFeed } from "./api";
import type { ContentTarget } from "./types";

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

/** Reads the page's feed again: after something was added to it, changed or taken away. */
export function useFeedRefresh(target: ContentTarget): () => void {
  const queryClient = useQueryClient();
  const { type, id } = target;
  return useCallback(() => void queryClient.invalidateQueries({ queryKey: contentFeedKey({ type, id }) }), [queryClient, type, id]);
}
