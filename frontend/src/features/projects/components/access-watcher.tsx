"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { apiFetch } from "@/lib/api/client";
import type { ProjectEntry } from "../types";

/**
 * On the page that says "you are not in this project": looks again whenever the page is shown (opened, brought back
 * from the screens the app keeps alive behind the current one, or switched back to from another window) and sends the
 * person into the project as soon as they have a role. The server renders this page, so without it a page that was left
 * open while an administrator let the person in would still say no until it was reloaded.
 */
export function AccessWatcher({ projectId }: { projectId: string }) {
  const router = useRouter();
  useEffect(() => {
    let stopped = false;
    const look = async () => {
      try {
        const entry = await apiFetch<ProjectEntry>(`/api/v1/projects/${encodeURIComponent(projectId)}/access`);
        if (!stopped && entry.myRole) router.replace(`/project/${entry.id}`);
      } catch {
        // The page is still what it was; the next look may do better.
      }
    };
    const lookWhenSeen = () => {
      if (document.visibilityState === "visible") void look();
    };
    void look();
    document.addEventListener("visibilitychange", lookWhenSeen);
    window.addEventListener("focus", lookWhenSeen);
    return () => {
      stopped = true;
      document.removeEventListener("visibilitychange", lookWhenSeen);
      window.removeEventListener("focus", lookWhenSeen);
    };
  }, [projectId, router]);
  return null;
}
