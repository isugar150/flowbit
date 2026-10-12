"use client";

import { useEffect, useRef } from "react";

/**
 * Keeps an uncontrolled text field in step with the server's value, which changes when someone
 * else edits it (realtime refresh). The field is left alone while it has unsaved typing, and
 * while it is empty, so a person clearing it to retype is not interrupted.
 */
export function useRemoteValue<T extends HTMLInputElement>(value: string, isDirty: (input: T) => boolean) {
  const ref = useRef<T>(null);
  useEffect(() => {
    const input = ref.current;
    if (!input || isDirty(input) || input.value === value || input.value === "") return;
    input.value = value;
  }, [value, isDirty]);
  return ref;
}
