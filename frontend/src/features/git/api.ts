import { apiFetch } from "@/lib/api/client";
import type { GitConnection, GitConnectionInput, GitHubManifestForm, GitReceiptResult } from "./types";

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

/** The form that makes the connection's GitHub App on GitHub; `origin` is where this browser has Flowbit. */
export function githubManifest(id: string, origin: string): Promise<GitHubManifestForm> {
  return apiFetch<GitHubManifestForm>(`${ADMIN}/${id}/github/manifest?origin=${encodeURIComponent(origin)}`);
}

/** GitHub sent the browser back after the app was made. */
export function githubCreated(code: string, state: string): Promise<GitConnection> {
  return apiFetch<GitConnection>("/api/v1/admin/git/github/created", { method: "POST", body: { code, state } });
}

/** GitHub sent the browser back after the app was installed: every GitHub connection reads where it is installed. */
export function githubInstallations(): Promise<GitConnection[]> {
  return apiFetch<GitConnection[]>("/api/v1/admin/git/github/installations", { method: "POST" });
}

export function githubInstallation(id: string): Promise<GitConnection> {
  return apiFetch<GitConnection>(`${ADMIN}/${id}/github/installation`, { method: "POST" });
}

/** Reads the GitHub API now instead of at the next round. */
export function syncGitConnection(id: string): Promise<GitReceiptResult> {
  return apiFetch<GitReceiptResult>(`${ADMIN}/${id}/sync`, { method: "POST" });
}
