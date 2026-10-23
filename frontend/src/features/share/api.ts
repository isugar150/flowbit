import { apiFetch } from "@/lib/api/client";
import type { PublicShare, ShareLink, ShareTarget } from "./types";

/** The link for this page: the person's own if they have one, so the same page is always the same address. */
export function createShare(target: ShareTarget): Promise<ShareLink> {
  return apiFetch<ShareLink>("/api/v1/shares", { method: "POST", body: target });
}

/** Stops the link: it opens no more, and sharing the page again makes a new one. */
export function revokeShare(id: string): Promise<void> {
  return apiFetch<void>(`/api/v1/shares/${id}`, { method: "DELETE" });
}

/** What a link shows. Needs no sign-in. */
export function fetchPublicShare(token: string): Promise<PublicShare> {
  return apiFetch<PublicShare>(`/api/v1/public/shares/${encodeURIComponent(token)}`);
}

/** A page under the shared one: a sub-issue (by number) or a document (by id) of a shared issue. */
export function fetchPublicItem(token: string, type: "issue" | "document", id: string): Promise<PublicShare> {
  return apiFetch<PublicShare>(`/api/v1/public/shares/${encodeURIComponent(token)}/items/${type}/${encodeURIComponent(id)}`);
}
