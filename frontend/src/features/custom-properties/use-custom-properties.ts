"use client";

import { useEffect } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import type { IssuePerson } from "@/features/issues/types";
import { addCustomProperty, changeCustomProperty, customPropertiesKey, getCustomProperties, removeCustomProperty } from "./api";
import type { CustomPropertyChanges, CustomPropertyList, CustomPropertyOwner, CustomPropertyType } from "./types";

/**
 * The properties people added to an item, and what changes them. A change shows at once and is
 * saved behind it; if the server refuses, the old value comes back and `error` says why. `watch` is
 * what the server last rendered the page from: when it changes (someone else changed the page, and
 * it was read again), the properties are read again too (the same as the activity under a page).
 */
export function useCustomProperties(owner: CustomPropertyOwner, watch?: unknown) {
  const queryClient = useQueryClient();
  const key = customPropertiesKey(owner);
  const query = useQuery({ queryKey: key, queryFn: () => getCustomProperties(owner), staleTime: 0 });
  const refetch = query.refetch;
  useEffect(() => {
    // Not while a reading is already on its way (the first one, when the page opens).
    void refetch({ cancelRefetch: false });
  }, [watch, refetch]);

  /** Applies `edit` to the cached list; returns what to put back if the server says no. */
  const show = async (edit: (list: CustomPropertyList) => CustomPropertyList) => {
    await queryClient.cancelQueries({ queryKey: key });
    const previous = queryClient.getQueryData<CustomPropertyList>(key);
    if (previous) queryClient.setQueryData<CustomPropertyList>(key, edit(previous));
    return { previous };
  };
  const putBack = (context: { previous?: CustomPropertyList } | undefined) => {
    if (context?.previous) queryClient.setQueryData(key, context.previous);
  };
  const settled = () => void queryClient.invalidateQueries({ queryKey: key });

  const add = useMutation({
    mutationFn: (input: { label: string; type: CustomPropertyType }) => addCustomProperty({ owner, ...input }),
    onSettled: settled,
  });
  const change = useMutation({
    // `person` is who a picked PERSON value is, so that the face shows before the server answers.
    mutationFn: (input: { id: string; changes: CustomPropertyChanges; person?: IssuePerson | null }) =>
      changeCustomProperty({ id: input.id, changes: input.changes }),
    onMutate: ({ id, changes, person }) =>
      show((list) => ({
        ...list,
        items: list.items.map((item) =>
          item.id === id ? { ...item, ...changes, ...(person === undefined ? null : { person }) } : item,
        ),
      })),
    onError: (_error, _input, context) => putBack(context),
    onSettled: settled,
  });
  const remove = useMutation({
    mutationFn: (id: string) => removeCustomProperty(id),
    onMutate: (id) => show((list) => ({ ...list, items: list.items.filter((item) => item.id !== id) })),
    onError: (_error, _id, context) => putBack(context),
    onSettled: settled,
  });

  // What adding says goes with the form that adds; the others are shown with the list.
  return { query, add, change, remove, error: change.error ?? remove.error };
}
