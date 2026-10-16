/** docs/features/git-integration.md. Gitea covers Forgejo too. */
export type GitProvider = "GITEA" | "GITLAB" | "GITHUB";

/**
 * In the order the connection form offers them (the user's call): GitHub, connected as a GitHub
 * App (D-146), then GitLab and Gitea, connected by a webhook address and secret.
 */
export const GIT_PROVIDERS: GitProvider[] = ["GITHUB", "GITLAB", "GITEA"];

/** WEBHOOK: the server calls Flowbit. POLL: Flowbit reads the GitHub API every few minutes (GitHub only). */
export type GitReceiveMode = "WEBHOOK" | "POLL";

/** A connection's GitHub App and where it is installed. */
export type GitHubApp = {
  organization: string | null;
  /** Null until the app is made. */
  appId: number | null;
  appSlug: string | null;
  appUrl: string | null;
  installUrl: string | null;
  settingsUrl: string | null;
  /** Where members come back after connecting their GitHub account; must be one of the app's Callback URLs. */
  callbackUrl: string;
  /** Null until the app is installed on the organization. */
  installationId: number | null;
  /** "all" or "selected". */
  repositorySelection: string | null;
};

/** What the last call from the Git server did. */
export type GitReceiptResult = {
  /** Keys of the issues it linked, such as FB-12. */
  linkedIssues: string[];
  statusChanged: string[];
  /** Issues past the limit of one event, left alone. */
  skipped: number;
};

/** GET /api/v1/admin/git/connections */
export type GitConnection = {
  id: string;
  name: string;
  enabled: boolean;
  provider: GitProvider;
  baseUrl: string;
  autoInProgress: boolean;
  autoDone: boolean;
  secretSet: boolean;
  /** What the Git server calls; a path alone (starting with "/") when FLOWBIT_PUBLIC_URL is not set. */
  webhookUrl: string;
  lastReceivedAt: string | null;
  lastEvent: string | null;
  lastResult: GitReceiptResult | null;
  lastError: string | null;
  createdAt: string;
  updatedAt: string;
  receiveMode: GitReceiveMode;
  /** GitHub connections only. */
  github?: GitHubApp;
  /** When the GitHub API was last read (POLL), what went wrong then, and the calls left this hour. */
  lastSyncedAt: string | null;
  syncError: string | null;
  rateLimitRemaining: number | null;
  /** Only in the response that made the connection (not for GitHub, whose app holds its own). */
  secret?: string;
};

export type GitConnectionInput = {
  name: string;
  enabled: boolean;
  provider: GitProvider;
  baseUrl: string;
  autoInProgress: boolean;
  autoDone: boolean;
  receiveMode: GitReceiveMode;
  /** GitHub only: the organization the app is made for. */
  githubOrganization: string;
};

/** GET /api/v1/me/git/github (D-150) */
export type MyGitHubAccount = {
  /** Whether there is a GitHub App to connect through; without one the setting is not shown. */
  available: boolean;
  login: string | null;
  linkedAt: string | null;
};

/** What the browser posts to GitHub to make the app. */
export type GitHubManifestForm = { action: string; manifest: string };

export type GitPrState = "OPEN" | "DRAFT" | "MERGED" | "CLOSED";

/** A repository on one of the Git connections. */
export type GitRepositoryRef = { connectionId: string; repository: string };

/** A repository an issue's work can be in, with its page on the Git server (D-148). */
export type IssueGitRepository = GitRepositoryRef & { url: string };

/** GET /api/v1/projects/{id}/git */
export type ProjectGitSettings = {
  /** Whether Git is in use at all; without it the settings are not shown. */
  enabled: boolean;
  connections: { id: string; name: string; provider: GitProvider }[];
  repositories: GitRepositoryRef[];
  /**
   * Repositories to pick from, by connection (D-151): a GitHub App's installed repositories and
   * those that sent events. The project's own are left out.
   */
  available: GitRepositoryRef[];
};

/** A branch or pull request an issue is linked to (GET /api/v1/issues/{number}, `git`). */
export type IssueGitLink = {
  kind: "BRANCH" | "PULL_REQUEST";
  /** org/web */
  repository: string;
  branch: string | null;
  prNumber: number | null;
  title: string | null;
  url: string;
  prState: GitPrState | null;
  /** Merging it finishes the issue; otherwise it only refers to it. */
  closes: boolean;
};
