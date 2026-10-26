"use client";

import { useState } from "react";
import {
  FolderGit2Icon,
  GitBranchIcon,
  GitMergeIcon,
  GitPullRequestClosedIcon,
  GitPullRequestDraftIcon,
  GitPullRequestIcon,
  InfoIcon,
  UnlinkIcon,
} from "lucide-react";
import { Button } from "@/components/ui/button";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { apiErrorMessage } from "@/lib/api/error-message";
import { useRefreshingMutation } from "@/lib/use-refreshing-mutation";
import { cn } from "@/lib/utils";
import { messages } from "@/messages";
import { unlinkGit } from "../api";
import type { GitPrState, IssueGitLink, IssueGitRepository } from "../types";
import { IssueGitRepositoryPicker } from "./issue-git-repository";

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
 * Takes a link off the issue (D-161), on a second press: the first turns the button into a
 * "해제" that does it, and leaving the button puts it back.
 */
function UnlinkButton({ number, link, label }: { number: number; link: IssueGitLink; label: string }) {
  const [armed, setArmed] = useState(false);
  const unlink = useRefreshingMutation(unlinkGit);
  return (
    <>
      <Button
        type="button"
        variant={armed ? "destructive" : "ghost"}
        size={armed ? "sm" : "icon-sm"}
        aria-label={armed ? m.unlinkConfirm : m.unlink(label)}
        title={armed ? undefined : m.unlink(label)}
        disabled={unlink.isPending}
        className={cn("shrink-0 max-md:h-11", !armed && "text-muted-foreground opacity-0 group-hover:opacity-100 focus-visible:opacity-100 max-md:size-11 max-md:opacity-100")}
        onBlur={() => setArmed(false)}
        onClick={() => (armed ? unlink.mutate({ number, linkId: link.id }) : setArmed(true))}
      >
        {armed ? m.unlinkConfirm : <UnlinkIcon />}
      </Button>
      {unlink.isError ? (
        <p role="alert" className="basis-full text-xs leading-4 text-destructive">
          {apiErrorMessage(unlink.error)}
        </p>
      ) : null}
    </>
  );
}

/** The (i) beside the heading: what to write in Git so that this issue moves along (D-210). Shows on hover and on focus. */
function GitHelp({ issueKey }: { issueKey: string }) {
  return (
    <Tooltip>
      <TooltipTrigger
        delay={0}
        render={
          <button
            type="button"
            aria-label={m.help.label}
            className="flex size-5 items-center justify-center rounded-full text-muted-foreground outline-none hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50 max-md:size-8"
          />
        }
      >
        <InfoIcon aria-hidden className="size-3.5" />
      </TooltipTrigger>
      <TooltipContent side="left" className="flex max-w-64 flex-col gap-1 px-2.5 py-2 text-left leading-4">
        <span className="font-medium">{m.help.intro(issueKey)}</span>
        <ul className="flex flex-col gap-0.5">
          {m.help.steps(issueKey).map((step) => (
            <li key={step} className="flex gap-1.5">
              <span aria-hidden="true">•</span>
              <span>{step}</span>
            </li>
          ))}
        </ul>
        <span className="opacity-80">{m.help.note}</span>
      </TooltipContent>
    </Tooltip>
  );
}

/**
 * The "Git" group of an issue's side panel (docs/features/git-integration.md "화면"): which of its
 * project's repositories the work is in, picked when there are several to pick from (D-148) and
 * otherwise shown as the one its first branch or pull request came from (D-153), and the pull
 * requests and branches a Git server linked to it, each opening on the Git server in a new tab.
 * Nothing at all when there is neither, so an installation without Git never sees it.
 */
export function IssueGitLinks({
  number,
  issueKey,
  links,
  repository = null,
  repositoryOptions = [],
  canEdit = false,
}: {
  number: number;
  /** FB-12: the key Git has to carry, for the examples in the help. */
  issueKey: string;
  links: IssueGitLink[];
  repository?: IssueGitRepository | null;
  repositoryOptions?: IssueGitRepository[];
  canEdit?: boolean;
}) {
  const picking = repositoryOptions.length > 1;
  // A repository alone is enough: a commit that names the issue picks it (D-209) before any branch or pull request exists.
  if (links.length === 0 && !picking && !repository) return null;
  return (
    <section className="flex flex-col gap-1">
      <div className="flex items-center gap-1">
        <h3 className="text-[0.8125rem] leading-5 font-medium text-muted-foreground">{m.heading}</h3>
        <GitHelp issueKey={issueKey} />
      </div>
      {picking ? (
        <IssueGitRepositoryPicker number={number} repository={repository} options={repositoryOptions} disabled={!canEdit} />
      ) : repository ? (
        <a
          href={repository.url}
          target="_blank"
          rel="noreferrer"
          aria-label={m.openRepository(repository.repository)}
          title={m.openRepository(repository.repository)}
          className="flex min-h-8 items-center gap-2 rounded-md px-1 text-[0.8125rem] leading-5 outline-none hover:bg-accent focus-visible:ring-3 focus-visible:ring-ring/50 max-md:min-h-11"
        >
          <FolderGit2Icon aria-hidden className="size-4 shrink-0 text-muted-foreground" />
          <span className="min-w-0 flex-1 truncate">{repository.repository}</span>
        </a>
      ) : null}
      <ul className="flex flex-col">
        {links.map((link) => {
          const pullRequest = link.kind === "PULL_REQUEST";
          const label = pullRequest
            ? m.pullRequestLabel(repositoryName(link.repository), link.prNumber ?? 0, link.title ?? "", link.prState ? m.states[link.prState] : "")
            : m.branchLabel(link.branch ?? "");
          return (
            <li key={link.id} className="group flex flex-wrap items-center gap-1">
              <a
                href={link.url}
                target="_blank"
                rel="noreferrer"
                aria-label={label}
                title={label}
                className="flex min-h-8 min-w-0 flex-1 items-center gap-2 rounded-md px-1 text-[0.8125rem] leading-5 outline-none hover:bg-accent focus-visible:ring-3 focus-visible:ring-ring/50 max-md:min-h-11"
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
              {canEdit ? <UnlinkButton number={number} link={link} label={label} /> : null}
            </li>
          );
        })}
      </ul>
    </section>
  );
}
