"use client";

import { useCallback, useEffect, useOptimistic, useRef, useState, useTransition } from "react";
import { useRouter } from "next/navigation";
import { useMutation } from "@tanstack/react-query";

/**
 * A mutation whose result is shown by server components: on success the current route is
 * re-rendered on the server so lists, the sidebar and the user menu pick up the change.
 *
 * It counts as pending until that re-render is on screen, and what `onSuccess` sets (an emptied
 * field, a closed form) lands in the same frame as the new data. Otherwise there is a moment in
 * between where the button is free again and the field is empty, but the page still shows what
 * it showed before: a sent comment would be in neither the box nor the list.
 */
export function useRefreshingMutation<TArgs, TResult = unknown>(
  mutationFn: (args: TArgs) => Promise<TResult>,
  onSuccess?: (result: TResult) => void,
) {
  const router = useRouter();
  const [refreshing, startTransition] = useTransition();
  const mutation = useMutation({
    mutationFn,
    onSuccess: (result) => {
      startTransition(() => {
        onSuccess?.(result);
        router.refresh();
      });
    },
  });
  return { ...mutation, isPending: mutation.isPending || refreshing };
}

/**
 * A change that shows before it is saved, for values picked in place (a status, a date, a role).
 * `value` is what the server last sent; what comes back is that with the changes still on their
 * way applied by `apply`. It holds until the re-render that follows the save is on screen, so the
 * value never falls back to the old one in between, and nothing needs to be disabled meanwhile.
 * A change the server refuses goes back by itself, and `error` says why.
 *
 * Changes are sent one after the other, in the order they were made: two sent side by side could
 * arrive the other way round and leave the first one's value behind.
 */
export function useOptimisticMutation<TValue, TArgs, TResult = unknown>(
  value: TValue,
  apply: (value: TValue, args: TArgs) => TValue,
  mutationFn: (args: TArgs) => Promise<TResult>,
) {
  const router = useRouter();
  const [shown, show] = useOptimistic(value, apply);
  const [isPending, startTransition] = useTransition();
  const [error, setError] = useState<unknown>(null);
  const queue = useRef<Promise<unknown>>(Promise.resolve());
  const mutationFnRef = useRef(mutationFn);
  useEffect(() => {
    mutationFnRef.current = mutationFn;
  }, [mutationFn]);

  const mutate = useCallback(
    (args: TArgs) => {
      setError(null);
      startTransition(async () => {
        show(args);
        const sent = queue.current.then(() => mutationFnRef.current(args));
        queue.current = sent.catch(() => undefined);
        try {
          await sent;
          // Started while this change is still pending, so React holds the shown value until the
          // refreshed page is ready and swaps both in one frame.
          startTransition(() => router.refresh());
        } catch (failure) {
          setError(failure);
        }
      });
    },
    [router, show],
  );
  const reset = useCallback(() => setError(null), []);

  return { value: shown, mutate, isPending, error, isError: error !== null, reset };
}
