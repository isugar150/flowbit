"use client";

import { useId, useState } from "react";
import { FolderGit2Icon, PlusIcon, XIcon } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { NativeSelect, NativeSelectOption } from "@/components/ui/native-select";
import { apiErrorMessage } from "@/lib/api/error-message";
import { useRefreshingMutation } from "@/lib/use-refreshing-mutation";
import { messages } from "@/messages";
import { saveProjectGitRepositories } from "../api";
import type { GitRepositoryRef, ProjectGitSettings } from "../types";

const m = messages.git.repositories;

/**
 * The repositories a project's work happens in (D-148), for its managers. With any set, the
 * project's issues take Git events only from them, whatever the connection's allowed projects
 * say; with several, each issue can pick one. Every change is saved at once.
 */
export function ProjectGitRepositories({ projectId, settings }: { projectId: string; settings: ProjectGitSettings }) {
  const id = useId();
  const [connectionId, setConnectionId] = useState(settings.connections[0]?.id ?? "");
  const [repository, setRepository] = useState("");
  const save = useRefreshingMutation(saveProjectGitRepositories, () => setRepository(""));
  const nameOf = (ref: GitRepositoryRef) => settings.connections.find((it) => it.id === ref.connectionId)?.name ?? "";
  const replace = (repositories: GitRepositoryRef[]) => save.mutate({ projectId, repositories });
  const suggestions = settings.suggestions.filter((it) => it.connectionId === connectionId);

  return (
    <div className="flex flex-col gap-4">
      {settings.repositories.length === 0 ? (
        <p className="text-muted-foreground">{m.empty}</p>
      ) : (
        <ul className="flex flex-col divide-y rounded-lg border">
          {settings.repositories.map((ref) => (
            <li key={`${ref.connectionId}|${ref.repository}`} className="flex items-center gap-3 px-4 py-2">
              <FolderGit2Icon aria-hidden className="size-4 shrink-0 text-muted-foreground" />
              <span className="min-w-0 flex-1 truncate font-mono text-[0.8125rem]">{ref.repository}</span>
              <span className="shrink-0 text-[0.8125rem] text-muted-foreground">{nameOf(ref)}</span>
              <Button
                type="button"
                variant="ghost"
                size="icon-sm"
                className="max-md:size-11"
                aria-label={m.remove(ref.repository)}
                disabled={save.isPending}
                onClick={() => replace(settings.repositories.filter((other) => other !== ref))}
              >
                <XIcon />
              </Button>
            </li>
          ))}
        </ul>
      )}
      <form
        noValidate
        className="flex flex-wrap items-end gap-2"
        onSubmit={(event) => {
          event.preventDefault();
          if (!connectionId || !repository.trim()) return;
          replace([...settings.repositories, { connectionId, repository: repository.trim() }]);
        }}
      >
        {settings.connections.length > 1 ? (
          <label className="flex flex-col gap-1 text-sm font-medium">
            {m.connection}
            <NativeSelect className="h-9 max-md:h-11" value={connectionId} onChange={(event) => setConnectionId(event.target.value)}>
              {settings.connections.map((connection) => (
                <NativeSelectOption key={connection.id} value={connection.id}>
                  {connection.name}
                </NativeSelectOption>
              ))}
            </NativeSelect>
          </label>
        ) : null}
        <label className="flex min-w-56 flex-1 flex-col gap-1 text-sm font-medium">
          {m.repository}
          <Input
            value={repository}
            list={`${id}-suggestions`}
            placeholder={m.placeholder}
            className="h-9 font-mono max-md:h-11"
            autoComplete="off"
            autoCapitalize="none"
            spellCheck={false}
            onChange={(event) => {
              setRepository(event.target.value);
              save.reset();
            }}
          />
          <datalist id={`${id}-suggestions`}>
            {suggestions.map((it) => (
              <option key={it.repository} value={it.repository} />
            ))}
          </datalist>
        </label>
        <Button type="submit" variant="outline" className="h-9 max-md:h-11" disabled={save.isPending || !repository.trim()}>
          <PlusIcon data-icon="inline-start" />
          {m.add}
        </Button>
      </form>
      {save.isError ? (
        <p role="alert" className="text-[0.8125rem] leading-5 text-destructive">
          {apiErrorMessage(save.error)}
        </p>
      ) : null}
    </div>
  );
}
