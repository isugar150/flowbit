"use client";

import { useEffect } from "react";
import { usePathname, useRouter } from "next/navigation";
import { markInboxPathRead } from "../api";

/**
 * Opening a page reads what the person was told about it (D-217): a ticket, a document or a project that has an unread
 * notification stops having one the moment it is on screen, whichever way they came (the bell, a link in a mail or a
 * push, an address typed in). Renders nothing. It asks only while something is unread and the tab is in front, and again
 * when the tab is brought to the front or a new notification arrives for the page that is open. The count in the bell
 * follows by the page being read again.
 */
export function InboxSeen({ unread }: { unread: number }) {
  const pathname = usePathname();
  const router = useRouter();
  useEffect(() => {
    if (unread === 0) return;
    let stopped = false;
    const mark = async () => {
      if (document.visibilityState !== "visible") return;
      try {
        const { marked } = await markInboxPathRead(pathname);
        if (!stopped && marked > 0) router.refresh();
      } catch {
        // The bell keeps its count; the next page opened asks again.
      }
    };
    void mark();
    document.addEventListener("visibilitychange", mark);
    return () => {
      stopped = true;
      document.removeEventListener("visibilitychange", mark);
    };
  }, [pathname, unread, router]);
  return null;
}
