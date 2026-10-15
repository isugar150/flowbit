"use client";

import { FolderGit2Icon } from "lucide-react";
import { ChipSelect, type ChipOption } from "@/features/issues/components/chip-select";
import { apiErrorMessage } from "@/lib/api/error-message";
import { useOptimisticMutation } from "@/lib/use-refreshing-mutation";
import { messages } from "@/messages";
import { setIssueGitRepository } from "../api";
import type { GitRepositoryRef, IssueGitRepository } from "../types";

const m = messages.git.panel;
const NONE = "";

const keyOf = (ref: GitRepositoryRef) => `${ref.connectionId}|${ref.repository}`;

/**
 * Which of its project's repositories the issue's work is in (D-148). Shown when the project has
 * more than one; once picked, only events from that repository reach the issue.
 */
export function IssueGitRepositoryPicker({
  number,
  repository,
  options,
  disabled,
}: {
  number: number;
  repository: IssueGitRepository | null;
  options: IssueGitRepository[];
  disabled: boolean;
}) {
  const mutation = useOptimisticMutation(
    repository ? keyOf(repository) : NONE,
    (_, next: { number: number; repository: GitRepositoryRef | null }) => (next.repository ? keyOf(next.repository) : NONE),
    setIssueGitRepository,
  );
  const icon = <FolderGit2Icon aria-hidden className="size-4 text-muted-foreground" />;
  const choices: ChipOption<string>[] = [
    { value: NONE, label: m.noRepository, muted: true, icon },
    ...options.map((option) => ({ value: keyOf(option), label: option.repository, icon })),
  ];

  return (
    <div className="flex flex-col">
      <div className="flex min-h-8 items-center">
        <ChipSelect
          plain
          label={m.repository}
          value={mutation.value}
          options={choices}
          disabled={disabled}
          onChange={(value) => {
            const picked = options.find((option) => keyOf(option) === value);
            mutation.mutate({ number, repository: picked ? { connectionId: picked.connectionId, repository: picked.repository } : null });
          }}
        />
      </div>
      {mutation.isError ? <p className="text-xs leading-4 text-destructive">{apiErrorMessage(mutation.error)}</p> : null}
    </div>
  );
}
