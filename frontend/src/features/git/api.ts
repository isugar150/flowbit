import { apiFetch } from "@/lib/api/client";
import type {
  GitAccount,
  GitConnection,
  GitConnectionInput,
  GitHubManifestForm,
  GitReceiptResult,
  GitRepositoryRef,
  MyGitHubAccount,
  ProjectGitSettings,
} from "./types";

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

/** Says which Git user a member is; an empty name takes it away. */
export function saveGitAccount({ userId, username }: { userId: string; username: string }): Promise<GitAccount> {
  return apiFetch<GitAccount>(`/api/v1/admin/git/accounts/${userId}`, { method: "PUT", body: { username } });
}

/** Replaces the repositories a project's work happens in (D-148). Its managers only. */
export function saveProjectGitRepositories({ projectId, repositories }: { projectId: string; repositories: GitRepositoryRef[] }): Promise<ProjectGitSettings> {
  return apiFetch<ProjectGitSettings>(`/api/v1/projects/${encodeURIComponent(projectId)}/git/repositories`, {
    method: "PUT",
    body: { repositories },
  });
}

/** Picks which of its project's repositories an issue's work is in; null takes the pick away. */
export function setIssueGitRepository({ number, repository }: { number: number; repository: GitRepositoryRef | null }): Promise<void> {
  return apiFetch<void>(`/api/v1/issues/${number}/git-repository`, { method: "PUT", body: repository ?? {} });
}

/** Where to send the browser so that GitHub asks the member (D-150). */
export function authorizeMyGitHub(origin: string): Promise<{ url: string }> {
  return apiFetch<{ url: string }>(`/api/v1/me/git/github/authorize?origin=${encodeURIComponent(origin)}`, { method: "POST" });
}

/** GitHub sent the browser back with a code. */
export function finishMyGitHub(code: string, state: string): Promise<MyGitHubAccount> {
  return apiFetch<MyGitHubAccount>("/api/v1/me/git/github/callback", { method: "POST", body: { code, state } });
}

export function unlinkMyGitHub(): Promise<void> {
  return apiFetch<void>("/api/v1/me/git/github", { method: "DELETE" });
}
