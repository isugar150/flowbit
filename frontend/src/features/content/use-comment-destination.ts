"use client";

import { useEffect, useState, useSyncExternalStore } from "react";
import { usePathname } from "next/navigation";
import { commentDestination } from "./comment-destination";

const listeners = new Set<() => void>();
let requested: string | null = null;
let revision = 0;

/** Next's same-page hash navigation does not always emit hashchange. */
export function notifyCommentNavigation(path: string) {
  requested = `${path}\n${++revision}`;
  listeners.forEach((listener) => listener());
}

function subscribe(listener: () => void) {
  listeners.add(listener);
  const changed = () => { requested = null; listener(); };
  window.addEventListener("hashchange", changed);
  window.addEventListener("popstate", changed);
  return () => {
    listeners.delete(listener);
    window.removeEventListener("hashchange", changed);
    window.removeEventListener("popstate", changed);
  };
}

const snapshot = () => requested ?? `${window.location.pathname}${window.location.hash}\n0`;
const serverSnapshot = () => "";

/** Waits for activity rendering, then scrolls once within this activity's own container. */
export function useCommentDestination() {
  const pathname = usePathname();
  const destination = useSyncExternalStore(subscribe, snapshot, serverSnapshot);
  const target = commentDestination(destination.split("\n")[0]);
  const commentId = target?.path.toLowerCase() === pathname.toLowerCase() ? target.id : null;
  const [root, ref] = useState<HTMLElement | null>(null);

  useEffect(() => {
    if (!root || !commentId) return;
    let frame = 0;
    let timer: ReturnType<typeof setTimeout> | undefined;
    let found: HTMLElement | null = null;
    const locate = () => {
      if (found) return;
      const element = root.querySelector<HTMLElement>(`[data-comment-id="${commentId}"]`);
      if (!element || element.getClientRects().length === 0) return;
      found = element;
      observer.disconnect();
      frame = requestAnimationFrame(() => {
        element.scrollIntoView({ block: "center", behavior: window.matchMedia("(prefers-reduced-motion: reduce)").matches ? "auto" : "smooth" });
        element.classList.add("comment-focus");
        timer = setTimeout(() => element.classList.remove("comment-focus"), 2500);
      });
    };
    const observer = new MutationObserver(locate);
    observer.observe(root, { childList: true, subtree: true, attributes: true, attributeFilter: ["hidden", "open", "class"] });
    locate();
    return () => {
      observer.disconnect();
      cancelAnimationFrame(frame);
      clearTimeout(timer);
      found?.classList.remove("comment-focus");
    };
  }, [commentId, destination, root]);

  return { commentId, attachContainer: ref };
}
