"use client";

import { useState } from "react";
import { FolderGit2Icon, PlusIcon, RefreshCwIcon, XIcon } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { NativeSelect, NativeSelectOption } from "@/components/ui/native-select";
import { apiErrorMessage } from "@/lib/api/error-message";
import { useRefreshingMutation } from "@/lib/use-refreshing-mutation";
import { messages } from "@/messages";
import { refreshProjectGitRepositories, saveProjectGitRepositories } from "../api";
import type { GitRepositoryRef, ProjectGitSettings } from "../types";

const m = messages.git.repositories;

/** The repository select's choice that opens a field for a name the list does not have. */
const TYPE_IN = "\u0000type-in";

/**
 * The repositories a project's work happens in (D-148), for its managers. A connection is picked
 * first, then one of its repositories (D-151): a GitHub App's are the ones it is installed on, a
 * Gitea or GitLab connection's the ones that sent it events; a name the list does not have yet can
 * still be typed in. With any set, the project's issues take Git events only from them. Every
 * change is saved at once.
 */
export function ProjectGitRepositories({ projectId, settings }: { projectId: string; settings: ProjectGitSettings }) {
  const [connectionId, setConnectionId] = useState(settings.connections[0]?.id ?? "");
  const [picked, setPicked] = useState("");
  const [typed, setTyped] = useState("");
  const save = useRefreshingMutation(saveProjectGitRepositories, () => {
    setPicked("");
    setTyped("");
  });
  const reload = useRefreshingMutation(refreshProjectGitRepositories);
  const connection = settings.connections.find((it) => it.id === connectionId);
  const choices = settings.available.filter((it) => it.connectionId === connectionId);
  // Nothing to pick from yet (a Gitea or GitLab connection that has not heard from a repository): type it.
  const typing = choices.length === 0 || picked === TYPE_IN;
  const repository = (typing ? typed : picked).trim();
  const nameOf = (ref: GitRepositoryRef) => settings.connections.find((it) => it.id === ref.connectionId)?.name ?? "";
  const replace = (repositories: GitRepositoryRef[]) => save.mutate({ projectId, repositories });
  const failure = [save, reload].find((mutation) => mutation.isError);

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
          if (!connectionId || !repository) return;
          replace([...settings.repositories, { connectionId, repository }]);
        }}
      >
        <label className="flex flex-col gap-1 text-sm font-medium">
          {m.connection}
          <NativeSelect
            className="h-9 max-md:h-11"
            value={connectionId}
            onChange={(event) => {
              setConnectionId(event.target.value);
              setPicked("");
              save.reset();
            }}
          >
            {settings.connections.map((option) => (
              <NativeSelectOption key={option.id} value={option.id}>
                {option.name}
              </NativeSelectOption>
            ))}
          </NativeSelect>
        </label>
        {choices.length > 0 ? (
          <label className="flex min-w-56 flex-1 flex-col gap-1 text-sm font-medium">
            {m.repository}
            <NativeSelect
              className="h-9 w-full font-mono max-md:h-11"
              value={picked}
              onChange={(event) => {
                setPicked(event.target.value);
                save.reset();
              }}
            >
              <NativeSelectOption value="">{m.choose}</NativeSelectOption>
              {choices.map((it) => (
                <NativeSelectOption key={it.repository} value={it.repository}>
                  {it.repository}
                </NativeSelectOption>
              ))}
              <NativeSelectOption value={TYPE_IN}>{m.typeIn}</NativeSelectOption>
            </NativeSelect>
          </label>
        ) : null}
        {typing ? (
          <label className="flex min-w-56 flex-1 flex-col gap-1 text-sm font-medium">
            {choices.length > 0 ? m.typedName : m.repository}
            <Input
              value={typed}
              placeholder={m.placeholder}
              className="h-9 font-mono max-md:h-11"
              autoComplete="off"
              autoCapitalize="none"
              spellCheck={false}
              onChange={(event) => {
                setTyped(event.target.value);
                save.reset();
              }}
            />
          </label>
        ) : null}
        <Button type="submit" variant="outline" className="h-9 max-md:h-11" disabled={save.isPending || !repository}>
          <PlusIcon data-icon="inline-start" />
          {m.add}
        </Button>
        {connection?.provider === "GITHUB" ? (
          <Button
            type="button"
            variant="ghost"
            className="h-9 max-md:h-11"
            disabled={reload.isPending}
            onClick={() => reload.mutate(projectId)}
          >
            <RefreshCwIcon data-icon="inline-start" />
            {reload.isPending ? m.reloading : m.reload}
          </Button>
        ) : null}
      </form>
      {choices.length === 0 && connection && connection.provider !== "GITHUB" ? (
        <p className="text-[0.8125rem] leading-5 text-muted-foreground">{m.noneHeardYet}</p>
      ) : null}
      {failure ? (
        <p role="alert" className="text-[0.8125rem] leading-5 text-destructive">
          {apiErrorMessage(failure.error)}
        </p>
      ) : null}
    </div>
  );
}
