"use client";

import { useRouter } from "next/navigation";
import { useMutation } from "@tanstack/react-query";

/**
 * A mutation whose result is shown by server components: on success the current route is
 * re-rendered on the server so lists, the sidebar and the user menu pick up the change.
 */
export function useRefreshingMutation<TArgs, TResult = unknown>(
  mutationFn: (args: TArgs) => Promise<TResult>,
  onSuccess?: (result: TResult) => void,
) {
  const router = useRouter();
  return useMutation({
    mutationFn,
    onSuccess: (result) => {
      onSuccess?.(result);
      router.refresh();
    },
  });
}
