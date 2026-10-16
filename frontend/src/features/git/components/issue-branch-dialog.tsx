"use client";

import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { ExternalLinkIcon, GitBranchPlusIcon } from "lucide-react";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogTitle,
} from "@/components/ui/dialog";
import {
  Field,
  FieldDescription,
  FieldError,
  FieldLabel,
} from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import {
  NativeSelect,
  NativeSelectOption,
} from "@/components/ui/native-select";
import { apiErrorMessage, fieldErrorsOf } from "@/lib/api/error-message";
import { copyText } from "@/lib/copy-text";
import { useRefreshingMutation } from "@/lib/use-refreshing-mutation";
import { messages } from "@/messages";
import { createGitBranch, gitBranchTargets } from "../api";
import type { GitBranchCreated, GitBranchTarget } from "../types";

const m = messages.git.branchCreate;

const keyOf = (target: GitBranchTarget) =>
  `${target.connectionId}|${target.repository}`;

/** GitLab fills in its "new branch" page from the address; Gitea's branches page cannot be filled in. */
function pageOf(target: GitBranchTarget, branch: string): string {
  return target.mode === "PAGE"
    ? `${target.url}/-/branches/new?branch_name=${encodeURIComponent(branch)}`
    : `${target.url}/branches`;
}

/**
 * Makes the issue's branch (D-159). On GitHub Flowbit makes it from the default branch and links
 * it at once; on GitLab the server's new-branch page opens with the name filled in, and on Gitea
 * the name is copied and its branches open, since Flowbit has no token for those servers. The name
 * starts as the one the key's menu copies and must keep the issue's key.
 */
export function IssueBranchDialog({
  number,
  suggested,
  open,
  onOpenChange,
}: {
  number: number;
  suggested: string;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  const targets = useQuery({
    queryKey: ["git-branch-targets", number],
    queryFn: () => gitBranchTargets(number),
    enabled: open,
    staleTime: 0,
  });
  const [picked, setPicked] = useState<string | null>(null);
  const [branch, setBranch] = useState(suggested);
  const [done, setDone] = useState<GitBranchCreated | null>(null);
  const [copyFailed, setCopyFailed] = useState(false);
  const create = useRefreshingMutation(createGitBranch, setDone);
  const list = targets.data ?? [];
  const target = list.find((it) => keyOf(it) === picked) ?? list[0];
  const name = branch.trim();
  const errors = fieldErrorsOf(create.error);

  const reset = (next: boolean) => {
    if (next) {
      setBranch(suggested);
      setDone(null);
      setPicked(null);
      setCopyFailed(false);
      create.reset();
    }
    onOpenChange(next);
  };

  return (
    <Dialog open={open} onOpenChange={reset}>
      <DialogContent className="sm:max-w-md">
        <DialogTitle>{m.title}</DialogTitle>
        {done ? (
          <>
            <DialogDescription>
              {done.existed ? m.existed(done.branch) : m.created(done.branch)}
            </DialogDescription>
            <DialogFooter>
              <Button
                variant="outline"
                render={<a href={done.url} target="_blank" rel="noreferrer" />}
                nativeButton={false}
              >
                <ExternalLinkIcon data-icon="inline-start" />
                {m.openOnGitHub}
              </Button>
              <Button onClick={() => reset(false)}>{m.close}</Button>
            </DialogFooter>
          </>
        ) : (
          <form
            noValidate
            className="flex flex-col gap-4"
            onSubmit={(event) => {
              event.preventDefault();
              if (!target || !name) return;
              if (target.mode === "API") {
                create.mutate({
                  number,
                  connectionId: target.connectionId,
                  repository: target.repository,
                  branch: name,
                });
                return;
              }
              // GitLab and Gitea: the server makes it, from the page opened here.
              const page = window.open(
                pageOf(target, name),
                "_blank",
                "noopener",
              );
              if (target.mode === "COPY") {
                void copyText(name, event.currentTarget).then((copied) =>
                  setCopyFailed(!copied),
                );
              }
              if (page !== null && target.mode === "PAGE") reset(false);
            }}
          >
            <DialogDescription>{m.description}</DialogDescription>
            {targets.isPending ? (
              <p className="text-muted-foreground">{m.loading}</p>
            ) : targets.isError ? (
              <p role="alert" className="text-destructive">
                {apiErrorMessage(targets.error)}
              </p>
            ) : list.length === 0 ? (
              <p className="text-muted-foreground">{m.noRepositories}</p>
            ) : (
              <>
                <Field>
                  <FieldLabel htmlFor="branch-repository">
                    {m.repository}
                  </FieldLabel>
                  <NativeSelect
                    id="branch-repository"
                    className="h-9 w-full font-mono max-md:h-11"
                    value={target ? keyOf(target) : ""}
                    onChange={(event) => {
                      setPicked(event.target.value);
                      create.reset();
                    }}
                  >
                    {list.map((it) => (
                      <NativeSelectOption key={keyOf(it)} value={keyOf(it)}>
                        {it.repository} · {it.connectionName}
                      </NativeSelectOption>
                    ))}
                  </NativeSelect>
                  {target && target.mode !== "API" ? (
                    <FieldDescription>
                      {target.mode === "PAGE" ? m.gitlabHint : m.giteaHint}
                    </FieldDescription>
                  ) : null}
                </Field>
                <Field data-invalid={errors.branch ? true : undefined}>
                  <FieldLabel htmlFor="branch-name">{m.name}</FieldLabel>
                  <Input
                    id="branch-name"
                    value={branch}
                    className="h-9 font-mono max-md:h-11"
                    autoComplete="off"
                    autoCapitalize="none"
                    spellCheck={false}
                    aria-invalid={errors.branch ? true : undefined}
                    onChange={(event) => {
                      setBranch(event.target.value);
                      create.reset();
                    }}
                  />
                  {errors.branch ? (
                    <FieldError>{errors.branch}</FieldError>
                  ) : (
                    <FieldDescription>{m.nameHint}</FieldDescription>
                  )}
                </Field>
              </>
            )}
            {create.isError && !errors.branch ? (
              <p
                role="alert"
                className="text-[0.8125rem] leading-5 text-destructive"
              >
                {apiErrorMessage(create.error)}
              </p>
            ) : null}
            {copyFailed ? (
              <p
                role="alert"
                className="text-[0.8125rem] leading-5 text-destructive"
              >
                {m.copyFailed}
              </p>
            ) : null}
            <DialogFooter>
              <Button
                type="button"
                variant="outline"
                onClick={() => reset(false)}
              >
                {m.cancel}
              </Button>
              <Button
                type="submit"
                disabled={!target || !name || create.isPending}
              >
                {target && target.mode !== "API" ? (
                  <ExternalLinkIcon data-icon="inline-start" />
                ) : (
                  <GitBranchPlusIcon data-icon="inline-start" />
                )}
                {create.isPending
                  ? m.creating
                  : target?.mode === "PAGE"
                    ? m.openGitLab
                    : target?.mode === "COPY"
                      ? m.copyAndOpen
                      : m.create}
              </Button>
            </DialogFooter>
          </form>
        )}
      </DialogContent>
    </Dialog>
  );
}
