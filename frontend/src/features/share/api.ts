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

/** The latest times a link of the person's was opened: 20 unless `limit` says (200 at most). */
export function fetchShareAccesses(id: string, limit?: number): Promise<ShareAccesses> {
  return apiFetch<ShareAccesses>(`/api/v1/shares/${id}/accesses${limit ? `?limit=${limit}` : ""}`);
}

/**
 * What a link shows. Needs no sign-in. `where` is what the shared page knows about where it is
 * shown, for the record of who opened the link: whether it is inside a frame, and of which page.
 */
export function fetchPublicShare(token: string, where: { embedded: boolean; referrer: string }): Promise<PublicShare> {
  return apiFetch<PublicShare>(`/api/v1/public/shares/${encodeURIComponent(token)}${whereQuery(where)}`);
}

/** A page under the shared one: a sub-issue (by number) or a document (by id) of a shared issue. */
export function fetchPublicItem(token: string, type: "issue" | "document", id: string, where: { embedded: boolean; referrer: string }): Promise<PublicShare> {
  return apiFetch<PublicShare>(`/api/v1/public/shares/${encodeURIComponent(token)}/items/${type}/${encodeURIComponent(id)}${whereQuery(where)}`);
}

function whereQuery(where: { embedded: boolean; referrer: string }): string {
  return where.embedded ? `?${new URLSearchParams({ embedded: "true", referrer: where.referrer })}` : "";
}
