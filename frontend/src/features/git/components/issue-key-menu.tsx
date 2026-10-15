"use client";

import { useState } from "react";
import { CheckIcon, ChevronDownIcon, ExternalLinkIcon, GitBranchIcon } from "lucide-react";
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from "@/components/ui/dropdown-menu";
import { messages } from "@/messages";
import type { IssueGitRepository } from "../types";

const m = messages.git.branchCopy;

/**
 * The issue's key above its title. With Git in use it opens a menu that copies the branch name to
 * start the work with (D-145); without Git it is plain text, so there is no Git button at all.
 */
export function IssueKeyMenu({
  issueKey,
  branch,
  repository = null,
}: {
  issueKey: string;
  branch: string | null;
  /** The repository the work is in, opened from the menu (D-148). */
  repository?: IssueGitRepository | null;
}) {
  const [copied, setCopied] = useState(false);
  const key = <span className="text-[0.8125rem] leading-5 text-muted-foreground tabular-nums">{issueKey}</span>;
  if (!branch) return key;

  return (
    <DropdownMenu onOpenChange={(open) => open && setCopied(false)}>
      <DropdownMenuTrigger
        aria-label={m.menuLabel(issueKey)}
        className="-mx-1 flex items-center gap-0.5 rounded-md px-1 text-[0.8125rem] leading-5 text-muted-foreground tabular-nums outline-none hover:bg-accent hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50 max-md:min-h-11"
      >
        {issueKey}
        <ChevronDownIcon aria-hidden className="size-3.5" />
      </DropdownMenuTrigger>
      <DropdownMenuContent align="start" className="w-72">
        <DropdownMenuItem
          closeOnClick={false}
          onClick={() => {
            void navigator.clipboard?.writeText(branch).then(() => setCopied(true));
          }}
        >
          {copied ? <CheckIcon aria-hidden /> : <GitBranchIcon aria-hidden />}
          <span className="flex min-w-0 flex-col">
            <span>{copied ? m.copied : m.copy}</span>
            <span className="truncate font-mono text-xs text-muted-foreground">{branch}</span>
          </span>
        </DropdownMenuItem>
        {repository ? (
          <DropdownMenuItem render={<a href={repository.url} target="_blank" rel="noreferrer" />}>
            <ExternalLinkIcon aria-hidden />
            {messages.git.panel.openRepository(repository.repository)}
          </DropdownMenuItem>
        ) : null}
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
