/** docs/features/git-integration.md. Gitea covers Forgejo too. */
export type GitProvider = "GITEA" | "GITLAB" | "GITHUB";

/** The kinds this version can receive events from; GitLab and GitHub come later. */
export const GIT_PROVIDERS: GitProvider[] = ["GITEA"];

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
  allProjects: boolean;
  projectIds: string[];
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
  /** Only in the response that made the connection. */
  secret?: string;
};

export type GitConnectionInput = {
  name: string;
  enabled: boolean;
  provider: GitProvider;
  baseUrl: string;
  allProjects: boolean;
  projectIds: string[];
  autoInProgress: boolean;
  autoDone: boolean;
};

export type GitPrState = "OPEN" | "DRAFT" | "MERGED" | "CLOSED";

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
