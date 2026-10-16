"use client";

import { useState } from "react";
import {
  CheckIcon,
  ChevronDownIcon,
  ExternalLinkIcon,
  GitBranchIcon,
  GitBranchPlusIcon,
} from "lucide-react";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { copyText } from "@/lib/copy-text";
import { messages } from "@/messages";
import type { IssueGitRepository } from "../types";
import { IssueBranchDialog } from "./issue-branch-dialog";

const m = messages.git.branchCopy;

/**
 * The issue's key above its title. With Git in use it opens a menu that copies the branch name to
 * start the work with (D-145) and, for people who may edit the issue, makes the branch on the Git
 * server (D-159); without Git it is plain text, so there is no Git button at all.
 */
export function IssueKeyMenu({
  number,
  issueKey,
  branch,
  repository = null,
  canCreateBranch = false,
}: {
  number: number;
  issueKey: string;
  branch: string | null;
  /** The repository the work is in, opened from the menu (D-148). */
  repository?: IssueGitRepository | null;
  canCreateBranch?: boolean;
}) {
  const [copied, setCopied] = useState<boolean | null>(null);
  const [creating, setCreating] = useState(false);
  const key = (
    <span className="text-[0.8125rem] leading-5 text-muted-foreground tabular-nums">
      {issueKey}
    </span>
  );
  if (!branch) return key;

  return (
    <>
      <DropdownMenu onOpenChange={(open) => open && setCopied(null)}>
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
            onClick={(event) => {
              void copyText(branch, event.currentTarget).then(setCopied);
            }}
          >
            {copied ? <CheckIcon aria-hidden /> : <GitBranchIcon aria-hidden />}
            <span className="flex min-w-0 flex-col">
              <span
                className={copied === false ? "text-destructive" : undefined}
              >
                {copied ? m.copied : copied === false ? m.failed : m.copy}
              </span>
              <span className="truncate font-mono text-xs text-muted-foreground">
                {branch}
              </span>
            </span>
          </DropdownMenuItem>
          {canCreateBranch ? (
            <DropdownMenuItem onClick={() => setCreating(true)}>
              <GitBranchPlusIcon aria-hidden />
              {messages.git.branchCreate.menu}
            </DropdownMenuItem>
          ) : null}
          {repository ? (
            <DropdownMenuItem
              render={
                <a href={repository.url} target="_blank" rel="noreferrer" />
              }
            >
              <ExternalLinkIcon aria-hidden />
              {messages.git.panel.openRepository(repository.repository)}
            </DropdownMenuItem>
          ) : null}
        </DropdownMenuContent>
      </DropdownMenu>
      {canCreateBranch ? (
        <IssueBranchDialog
          number={number}
          suggested={branch}
          open={creating}
          onOpenChange={setCreating}
        />
      ) : null}
    </>
  );
}
