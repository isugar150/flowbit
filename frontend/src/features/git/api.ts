import { apiFetch } from "@/lib/api/client";
import type { GitConnection, GitConnectionInput } from "./types";

const ADMIN = "/api/v1/admin/git/connections";

/** The response carries the secret, this once. */
export function createGitConnection(input: GitConnectionInput): Promise<GitConnection> {
  return apiFetch<GitConnection>(ADMIN, { method: "POST", body: input });
}

export function updateGitConnection({ id, input }: { id: string; input: GitConnectionInput }): Promise<GitConnection> {
  return apiFetch<GitConnection>(`${ADMIN}/${id}`, { method: "PUT", body: input });
}

export function deleteGitConnection(id: string): Promise<void> {
  return apiFetch<void>(`${ADMIN}/${id}`, { method: "DELETE" });
}

/** A new secret; the old one stops working at once. */
export function regenerateGitSecret(id: string): Promise<{ secret: string }> {
  return apiFetch<{ secret: string }>(`${ADMIN}/${id}/secret`, { method: "POST" });
}
