"use client";

import { useEffect, useRef } from "react";

/**
 * What the three issue lists (내 티켓, 전체 티켓, a project's issues) remember together: the last
 * filters, order and grouping the person used in any of them. The project and milestone filters
 * belong to one list and are not shared; nor is the search text.
 */
const SHARED_KEYS = ["status", "priority", "assignee", "label", "sort", "group", "completed"] as const;
const STORAGE_KEY = "flowbit.issue-list";

function read(): URLSearchParams | null {
  try {
    const saved = window.localStorage.getItem(STORAGE_KEY);
    return saved ? new URLSearchParams(saved) : null;
  } catch {
    return null;
  }
}

function write(params: URLSearchParams) {
  const shared = new URLSearchParams();
  for (const key of SHARED_KEYS) {
    const value = params.get(key);
    if (value) shared.set(key, value);
  }
  try {
    window.localStorage.setItem(STORAGE_KEY, shared.toString());
  } catch {
    // A browser that keeps nothing (a private window) simply starts plain every time.
  }
}

/**
 * Keeps a list's filters, order and grouping across the three lists and across visits, in this
 * browser (the user's call, 2026-10-15). A list opened with an empty address takes what was last
 * used; whatever the address then says is saved as the last used. An address that already says
 * something (a link that opens the list narrowed) is followed as it is.
 */
export function useRememberedListQuery({
  query,
  enabled,
  restore,
}: {
  /** The list's own query string. */
  query: string;
  /** False for lists that must not take part (the deleted issues, the tour's example). */
  enabled: boolean;
  /** Puts the remembered values into the address. */
  restore: (saved: URLSearchParams) => void;
}) {
  const started = useRef(false);
  useEffect(() => {
    if (!enabled) return;
    if (!started.current) {
      started.current = true;
      const saved = read();
      if (query === "" && saved && saved.size > 0) {
        restore(saved);
        return;
      }
    }
    write(new URLSearchParams(query));
    // `restore` is new on every render; only a change of the address matters here.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [query, enabled]);
}
