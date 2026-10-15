import { GitBranchIcon, GitMergeIcon, GitPullRequestClosedIcon, GitPullRequestDraftIcon, GitPullRequestIcon } from "lucide-react";
import { cn } from "@/lib/utils";
import { messages } from "@/messages";
import type { GitPrState, IssueGitLink } from "../types";

const m = messages.git.panel;

/** `acme/web` → `web`: the organization is the same for nearly every link. */
export function repositoryName(repository: string): string {
  return repository.slice(repository.lastIndexOf("/") + 1);
}

/** Each state has its own shape as well as its own colour, so colour is never the only cue. */
function PullRequestIcon({ state }: { state: GitPrState | null }) {
  const className = "size-4 shrink-0";
  switch (state) {
    case "DRAFT":
      return <GitPullRequestDraftIcon aria-hidden className={cn(className, "text-muted-foreground")} />;
    case "MERGED":
      return <GitMergeIcon aria-hidden className={cn(className, "text-primary")} />;
    case "CLOSED":
      return <GitPullRequestClosedIcon aria-hidden className={cn(className, "text-muted-foreground")} />;
    default:
      return <GitPullRequestIcon aria-hidden className={cn(className, "text-warning")} />;
  }
}

/**
 * The "Git" group of an issue's side panel (docs/features/git-integration.md "화면"): the pull
 * requests and branches a Git server linked to it, each opening on the Git server in a new tab.
 * Nothing at all when there are none, so an installation without Git never sees it.
 */
export function IssueGitLinks({ links }: { links: IssueGitLink[] }) {
  if (links.length === 0) return null;
  return (
    <section className="flex flex-col gap-1">
      <h3 className="text-[0.8125rem] leading-5 font-medium text-muted-foreground">{m.heading}</h3>
      <ul className="flex flex-col">
        {links.map((link) => {
          const pullRequest = link.kind === "PULL_REQUEST";
          const label = pullRequest
            ? m.pullRequestLabel(repositoryName(link.repository), link.prNumber ?? 0, link.title ?? "", link.prState ? m.states[link.prState] : "")
            : m.branchLabel(link.branch ?? "");
          return (
            <li key={`${link.kind}-${link.repository}-${link.prNumber ?? link.branch}`}>
              <a
                href={link.url}
                target="_blank"
                rel="noreferrer"
                aria-label={label}
                title={label}
                className="flex min-h-8 items-center gap-2 rounded-md px-1 text-[0.8125rem] leading-5 outline-none hover:bg-accent focus-visible:ring-3 focus-visible:ring-ring/50 max-md:min-h-11"
              >
                {pullRequest ? <PullRequestIcon state={link.prState} /> : <GitBranchIcon aria-hidden className="size-4 shrink-0 text-muted-foreground" />}
                {pullRequest ? (
                  <>
                    <span className="shrink-0 text-muted-foreground tabular-nums">
                      {repositoryName(link.repository)} #{link.prNumber}
                    </span>
                    <span className="min-w-0 flex-1 truncate">{link.title}</span>
                    {link.closes ? null : <span className="shrink-0 text-xs text-muted-foreground">{m.reference}</span>}
                  </>
                ) : (
                  <span className="min-w-0 flex-1 truncate font-mono text-xs">{link.branch}</span>
                )}
              </a>
            </li>
          );
        })}
      </ul>
    </section>
  );
}
