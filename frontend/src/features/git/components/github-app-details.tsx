"use client";

import { useMutation } from "@tanstack/react-query";
import { CheckCircle2Icon, CircleIcon, ExternalLinkIcon, RefreshCwIcon } from "lucide-react";
import { TimeAgo } from "@/components/dates";
import { Button } from "@/components/ui/button";
import { FieldSection } from "@/components/setting-fields";
import { FormAlert } from "@/features/auth/components/form-alert";
import { apiErrorMessage } from "@/lib/api/error-message";
import { useRefreshingMutation } from "@/lib/use-refreshing-mutation";
import { messages } from "@/messages";
import { githubInstallation, githubManifest, syncGitConnection } from "../api";
import type { GitConnection } from "../types";

const m = messages.git.github;

/**
 * Posts the manifest to GitHub the way GitHub expects it: a plain form submission from the
 * browser, which leaves Flowbit for GitHub's "Create GitHub App" page.
 */
function postToGitHub(action: string, manifest: string) {
  const form = document.createElement("form");
  form.method = "post";
  form.action = action;
  const field = document.createElement("input");
  field.type = "hidden";
  field.name = "manifest";
  field.value = manifest;
  form.appendChild(field);
  document.body.appendChild(form);
  form.submit();
}

function Step({ done, title, children }: { done: boolean; title: string; children?: React.ReactNode }) {
  return (
    <li className="flex gap-3">
      {done ? (
        <CheckCircle2Icon aria-hidden className="mt-0.5 size-4 shrink-0 text-primary" />
      ) : (
        <CircleIcon aria-hidden className="mt-0.5 size-4 shrink-0 text-muted-foreground" />
      )}
      <div className="flex min-w-0 flex-1 flex-col gap-2">
        <span className={done ? "font-medium" : "font-medium text-foreground"}>
          {title}
          <span className="sr-only">{done ? m.done : m.notDone}</span>
        </span>
        {children}
      </div>
    </li>
  );
}

/**
 * Setting up a GitHub connection (docs/features/git-integration.md "GitHub: GitHub App 으로 연결"):
 * make the app on GitHub from a manifest, install it on the organization, then either receive its
 * webhook or read the GitHub API every few minutes.
 */
export function GitHubAppDetails({ connection }: { connection: GitConnection }) {
  const app = connection.github;
  const create = useMutation({
    mutationFn: () => githubManifest(connection.id, window.location.origin),
    onSuccess: (form) => postToGitHub(form.action, form.manifest),
  });
  const check = useRefreshingMutation(githubInstallation);
  const sync = useRefreshingMutation(syncGitConnection);
  const made = app?.appId != null;
  const installed = app?.installationId != null;
  const failure = [create, check, sync].find((mutation) => mutation.isError);

  return (
    <div className="flex flex-col gap-7">
      <FieldSection title={m.title} description={m.description}>
        <ol className="flex flex-col gap-4 text-[0.8125rem] leading-5 sm:col-span-2">
          <Step done={made} title={made ? m.made(app?.appSlug ?? "") : m.make}>
            {made ? (
              app?.settingsUrl ? (
                <a href={app.settingsUrl} target="_blank" rel="noreferrer" className="flex w-fit items-center gap-1 text-muted-foreground underline-offset-2 hover:underline">
                  {m.appSettings}
                  <ExternalLinkIcon aria-hidden className="size-3.5" />
                </a>
              ) : null
            ) : (
              <>
                <p className="text-muted-foreground">{m.makeHint(app?.organization ?? "")}</p>
                <div>
                  <Button type="button" className="h-9 max-md:h-11" disabled={create.isPending} onClick={() => create.mutate()}>
                    {create.isPending ? m.making : m.makeButton}
                  </Button>
                </div>
              </>
            )}
          </Step>
          <Step done={installed} title={installed ? m.installed(app?.repositorySelection === "all") : m.install}>
            {made ? (
              <>
                <p className="text-muted-foreground">{installed ? m.installedHint : m.installHint}</p>
                {installed && app?.callbackUrl ? <p className="text-muted-foreground">{m.callbackHint(app.callbackUrl)}</p> : null}
                <div className="flex flex-wrap gap-2">
                  {app?.installUrl ? (
                    <Button
                      variant={installed ? "outline" : "default"}
                      className="h-9 max-md:h-11"
                      nativeButton={false}
                      render={<a href={app.installUrl} />}
                    >
                      {installed ? m.changeRepositories : m.installButton}
                    </Button>
                  ) : null}
                  <Button type="button" variant="outline" className="h-9 max-md:h-11" disabled={check.isPending} onClick={() => check.mutate(connection.id)}>
                    <RefreshCwIcon data-icon="inline-start" />
                    {m.checkInstallation}
                  </Button>
                </div>
              </>
            ) : null}
          </Step>
          <Step done={installed && (connection.receiveMode === "POLL" ? connection.lastSyncedAt !== null : connection.lastReceivedAt !== null)} title={m.receive[connection.receiveMode]}>
            {connection.receiveMode === "POLL" ? (
              <>
                <p className="text-muted-foreground">{m.pollHint}</p>
                {connection.lastSyncedAt ? (
                  <p className="text-muted-foreground">
                    {m.lastSynced} <TimeAgo iso={connection.lastSyncedAt} />
                    {connection.syncError ? ` · ${m.syncError(connection.syncError)}` : null}
                    {connection.rateLimitRemaining !== null ? ` · ${m.rateLimit(connection.rateLimitRemaining)}` : null}
                  </p>
                ) : null}
                {sync.data ? <p role="status">{m.synced(sync.data.linkedIssues.length, sync.data.statusChanged.length)}</p> : null}
                <div>
                  <Button type="button" variant="outline" className="h-9 max-md:h-11" disabled={!installed || sync.isPending} onClick={() => sync.mutate(connection.id)}>
                    <RefreshCwIcon data-icon="inline-start" />
                    {sync.isPending ? m.syncing : m.syncNow}
                  </Button>
                </div>
              </>
            ) : (
              <>
                <p className="text-muted-foreground">{m.webhookHint}</p>
                <p className="font-mono text-xs break-all text-muted-foreground">{connection.webhookUrl}</p>
                {connection.webhookUrl.startsWith("/") ? <FormAlert>{m.webhookNeedsPublicUrl}</FormAlert> : null}
              </>
            )}
          </Step>
        </ol>
        {failure ? (
          <div className="sm:col-span-2">
            <FormAlert>{apiErrorMessage(failure.error)}</FormAlert>
          </div>
        ) : null}
      </FieldSection>
    </div>
  );
}
