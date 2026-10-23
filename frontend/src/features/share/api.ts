import { apiFetch } from "@/lib/api/client";
import type { PublicShare, ShareAccesses, ShareLink, ShareTarget } from "./types";

/** The link for this page: the person's own if they have one, so the same page is always the same address. */
export function createShare(target: ShareTarget): Promise<ShareLink> {
  return apiFetch<ShareLink>("/api/v1/shares", { method: "POST", body: target });
}

/** Stops the link: it opens no more, and sharing the page again makes a new one. */
export function revokeShare(id: string): Promise<void> {
  return apiFetch<void>(`/api/v1/shares/${id}`, { method: "DELETE" });
}

export function fetchShareAccesses(id: string): Promise<ShareAccesses> {
  return apiFetch<ShareAccesses>(`/api/v1/shares/${id}/accesses`);
}

/**
 * What a link shows. Needs no sign-in. `where` is what the shared page knows about where it is
 * shown, for the record of who opened the link: whether it is inside a frame, and of which page.
 */
export function fetchPublicShare(token: string, where: { embedded: boolean; referrer: string }): Promise<PublicShare> {
  const query = where.embedded ? `?${new URLSearchParams({ embedded: "true", referrer: where.referrer })}` : "";
  return apiFetch<PublicShare>(`/api/v1/public/shares/${encodeURIComponent(token)}${query}`);
}
