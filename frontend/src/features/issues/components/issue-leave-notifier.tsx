"use client";

import { useEffect } from "react";
import { flushIssueNotifications } from "../api";

/** How long after leaving: a body edit saved on the way out reaches the server first. */
const SETTLE_MS = 1000;

/**
 * Renders nothing. When the issue is closed, or another issue takes its place, the server is told
 * to send the notification about what this person changed on it now (D-126). Without this it goes
 * after a minute of quiet, which is also what happens when the browser is simply closed.
 */
export function IssueLeaveNotifier({ number }: { number: number }) {
  useEffect(
    () => () => {
      window.setTimeout(() => {
        flushIssueNotifications(number).catch(() => undefined);
      }, SETTLE_MS);
    },
    [number],
  );
  return null;
}
