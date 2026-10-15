"use client";

import { useState } from "react";
import { CheckIcon, CopyIcon, PlusIcon } from "lucide-react";
import { TimeAgo } from "@/components/dates";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { FieldDescription } from "@/components/ui/field";
import { CheckSetting, FieldSection, SegmentedSetting, SelectSetting, TextSetting } from "@/components/setting-fields";
import { SettingsActions } from "@/components/settings-card";
import { FormAlert } from "@/features/auth/components/form-alert";
import type { ProjectSummary } from "@/features/projects/types";
import { apiErrorMessage, fieldErrorsOf } from "@/lib/api/error-message";
import { useRefreshingMutation } from "@/lib/use-refreshing-mutation";
import { messages } from "@/messages";
import { createGitConnection, deleteGitConnection, regenerateGitSecret, updateGitConnection } from "../api";
import { GIT_PROVIDERS, type GitConnection, type GitConnectionInput } from "../types";

const m = messages.git;

const EMPTY: GitConnectionInput = {
  name: "",
  enabled: true,
  provider: "GITEA",
  baseUrl: "",
  allProjects: true,
  projectIds: [],
  autoInProgress: true,
  autoDone: true,
};

function inputOf(connection: GitConnection): GitConnectionInput {
  const { name, enabled, provider, baseUrl, allProjects, projectIds, autoInProgress, autoDone } = connection;
  return { name, enabled, provider, baseUrl, allProjects, projectIds, autoInProgress, autoDone };
}

/**
 * The Git servers whose webhooks reach Flowbit (docs/features/git-integration.md "관리자 설정").
 * Each connection is a row that opens to its webhook address, secret, setup guide and settings.
 * A secret is only ever shown right after it was made, kept here until the page is left.
 */
export function GitConnectionsPanel({ connections, projects }: { connections: GitConnection[]; projects: ProjectSummary[] }) {
  const [open, setOpen] = useState<string | null>(null);
  const [secrets, setSecrets] = useState<Record<string, string>>({});
  const reveal = (id: string, secret: string) => {
    setSecrets((current) => ({ ...current, [id]: secret }));
    setOpen(id);
  };

  return (
    <div className="flex flex-col gap-4">
      {connections.length === 0 && open !== "new" ? <p className="text-muted-foreground">{m.empty}</p> : null}
      {connections.length > 0 ? (
        <ul className="flex flex-col divide-y rounded-lg border">
          {connections.map((connection) => (
            <li key={connection.id} className="flex flex-col gap-5 px-4 py-3">
              <div className="flex flex-wrap items-center justify-between gap-x-4 gap-y-2">
                <div className="flex min-w-0 flex-col">
                  <span className="flex items-center gap-2">
                    <span className="truncate font-medium">{connection.name}</span>
                    {connection.enabled ? null : <Badge variant="outline">{m.disabled}</Badge>}
                  </span>
                  <span className="truncate text-[0.8125rem] leading-5 text-muted-foreground">
                    {m.providers[connection.provider]} · {connection.baseUrl}
                  </span>
                  <span className="text-[0.8125rem] leading-5 text-muted-foreground">
                    <LastReceipt connection={connection} />
                  </span>
                </div>
                <Button
                  variant="ghost"
                  size="sm"
                  className="max-md:h-11"
                  aria-expanded={open === connection.id}
                  onClick={() => setOpen(open === connection.id ? null : connection.id)}
                >
                  {open === connection.id ? m.close : m.edit}
                </Button>
              </div>
              {open === connection.id ? (
                <div className="flex flex-col gap-7">
                  <WebhookDetails connection={connection} secret={secrets[connection.id]} onSecret={(secret) => reveal(connection.id, secret)} />
                  <ConnectionForm connection={connection} projects={projects} onDone={() => setOpen(null)} />
                </div>
              ) : null}
            </li>
          ))}
        </ul>
      ) : null}
      {open === "new" ? (
        <div className="flex flex-col gap-5 rounded-lg border px-4 py-4">
          <h4 className="font-medium">{m.newTitle}</h4>
          <ConnectionForm
            projects={projects}
            onDone={() => setOpen(null)}
            onCreated={(created) => created.secret && reveal(created.id, created.secret)}
          />
        </div>
      ) : (
        <div>
          <Button variant="outline" className="h-9 max-md:h-11" onClick={() => setOpen("new")}>
            <PlusIcon data-icon="inline-start" />
            {m.add}
          </Button>
        </div>
      )}
    </div>
  );
}

/** When the Git server last called, and what came of it. */
function LastReceipt({ connection }: { connection: GitConnection }) {
  if (!connection.lastReceivedAt) return <>{m.neverReceived}</>;
  const result = connection.lastResult;
  const outcome = connection.lastError
    ? m.lastError(connection.lastError)
    : result
      ? m.lastResult(result.linkedIssues.length, result.statusChanged.length)
      : m.lastIgnored;
  return (
    <>
      {m.lastReceived} <TimeAgo iso={connection.lastReceivedAt} />
      {connection.lastEvent ? ` · ${connection.lastEvent}` : null} · {outcome}
    </>
  );
}

/** The address and secret to put into the Git server, with where they go. */
function WebhookDetails({
  connection,
  secret,
  onSecret,
}: {
  connection: GitConnection;
  /** The secret, when it was just made; otherwise it is only known to be set. */
  secret?: string;
  onSecret: (secret: string) => void;
}) {
  const [confirming, setConfirming] = useState(false);
  const regenerate = useRefreshingMutation(regenerateGitSecret, ({ secret }) => {
    setConfirming(false);
    onSecret(secret);
  });
  const incomplete = connection.webhookUrl.startsWith("/");

  return (
    <div className="flex flex-col gap-7">
      <FieldSection title={m.sections.webhook}>
        <CopyField id={`git-${connection.id}-url`} label={m.webhookUrl} value={connection.webhookUrl} />
        {incomplete ? <FormAlert>{m.publicUrlMissing}</FormAlert> : null}
        {secret ? (
          <CopyField id={`git-${connection.id}-secret`} label={m.secret} value={secret} hint={m.secretOnce} />
        ) : (
          <div className="flex flex-col gap-2 sm:col-span-2">
            <span className="text-sm font-medium">{m.secret}</span>
            <span className="text-[0.8125rem] leading-5 text-muted-foreground">{connection.secretSet ? m.secretSet : m.secretMissing}</span>
          </div>
        )}
        <div className="flex flex-wrap items-center gap-2 sm:col-span-2">
          <Button
            type="button"
            variant="outline"
            size="sm"
            className="max-md:h-11"
            disabled={regenerate.isPending}
            onClick={() => (confirming ? regenerate.mutate(connection.id) : setConfirming(true))}
            onBlur={() => setConfirming(false)}
          >
            {confirming ? m.regenerateConfirm : m.regenerate}
          </Button>
          {regenerate.isError ? <span className="text-[0.8125rem] text-destructive">{apiErrorMessage(regenerate.error)}</span> : null}
        </div>
      </FieldSection>
      <FieldSection title={m.guide.title}>
        <ol className="flex list-decimal flex-col gap-1 pl-5 text-[0.8125rem] leading-5 text-muted-foreground sm:col-span-2">
          {m.guide.steps.map((step) => (
            <li key={step}>{step}</li>
          ))}
        </ol>
      </FieldSection>
    </div>
  );
}

/** A value to paste somewhere else, read-only, with a button that copies it. */
function CopyField({ id, label, value, hint }: { id: string; label: string; value: string; hint?: string }) {
  const [copied, setCopied] = useState(false);
  return (
    <div className="flex items-end gap-2 sm:col-span-2">
      <div className="min-w-0 flex-1">
        <TextSetting id={id} label={label} value={value} hint={hint} readOnly />
      </div>
      <Button
        type="button"
        variant="outline"
        className={hint ? "mb-6 h-9 max-md:h-11" : "h-9 max-md:h-11"}
        onClick={() => {
          void navigator.clipboard?.writeText(value).then(() => setCopied(true));
        }}
        onBlur={() => setCopied(false)}
      >
        {copied ? <CheckIcon data-icon="inline-start" /> : <CopyIcon data-icon="inline-start" />}
        {copied ? m.copied : m.copy}
      </Button>
    </div>
  );
}

function ConnectionForm({
  connection,
  projects,
  onDone,
  onCreated,
}: {
  connection?: GitConnection;
  projects: ProjectSummary[];
  onDone: () => void;
  onCreated?: (created: GitConnection) => void;
}) {
  const prefix = `git-${connection?.id ?? "new"}`;
  const [form, setForm] = useState<GitConnectionInput>(connection ? inputOf(connection) : EMPTY);
  const [confirmingDelete, setConfirmingDelete] = useState(false);
  const save = useRefreshingMutation(
    (input: GitConnectionInput) => (connection ? updateGitConnection({ id: connection.id, input }) : createGitConnection(input)),
    (saved) => (connection ? onDone() : onCreated?.(saved)),
  );
  const remove = useRefreshingMutation(deleteGitConnection, onDone);
  const errors = fieldErrorsOf(save.error);
  const set = <K extends keyof GitConnectionInput>(name: K, value: GitConnectionInput[K]) => {
    setForm((current) => ({ ...current, [name]: value }));
    save.reset();
  };
  const failure = [save, remove].find((mutation) => mutation.isError && Object.keys(fieldErrorsOf(mutation.error)).length === 0);

  return (
    <form
      noValidate
      className="flex flex-col gap-7"
      onSubmit={(event) => {
        event.preventDefault();
        save.mutate(form);
      }}
    >
      {failure ? <FormAlert>{apiErrorMessage(failure.error)}</FormAlert> : null}

      <FieldSection title={m.sections.server}>
        <TextSetting id={`${prefix}-name`} label={m.name} value={form.name} error={errors.name} onChange={(v) => set("name", v)} />
        <SelectSetting
          id={`${prefix}-provider`}
          label={m.provider}
          value={form.provider}
          options={GIT_PROVIDERS.map((value) => ({ value, label: m.providers[value] }))}
          onChange={(v) => set("provider", v)}
        />
        <TextSetting
          id={`${prefix}-baseUrl`}
          wide
          type="url"
          label={m.baseUrl}
          hint={m.baseUrlHint}
          placeholder="https://git.example.com"
          value={form.baseUrl}
          error={errors.baseUrl}
          onChange={(v) => set("baseUrl", v)}
        />
        <CheckSetting id={`${prefix}-enabled`} wide label={m.enabled} checked={form.enabled} onChange={(v) => set("enabled", v)} />
      </FieldSection>

      <FieldSection title={m.sections.projects} description={m.projectsHint}>
        <SegmentedSetting
          label={m.sections.projects}
          value={form.allProjects ? "all" : "chosen"}
          options={[
            { value: "all", label: m.allProjects },
            { value: "chosen", label: m.chosenProjects },
          ]}
          onChange={(v) => set("allProjects", v === "all")}
        />
        {form.allProjects ? null : (
          <fieldset className="flex flex-col gap-2 sm:col-span-2">
            <legend className="sr-only">{m.chosenProjects}</legend>
            {projects.length === 0 ? (
              <p className="text-[0.8125rem] text-muted-foreground">{m.noProjects}</p>
            ) : (
              <div className="grid grid-cols-1 gap-x-4 gap-y-1 sm:grid-cols-2">
                {projects.map((project) => (
                  <CheckSetting
                    key={project.id}
                    id={`${prefix}-project-${project.id}`}
                    label={project.name}
                    checked={form.projectIds.includes(project.id)}
                    onChange={(on) =>
                      set("projectIds", on ? [...form.projectIds, project.id] : form.projectIds.filter((id) => id !== project.id))
                    }
                  />
                ))}
              </div>
            )}
            {errors.projectIds ? <FieldDescription className="text-destructive">{errors.projectIds}</FieldDescription> : null}
          </fieldset>
        )}
      </FieldSection>

      <FieldSection title={m.sections.status}>
        <CheckSetting
          id={`${prefix}-autoInProgress`}
          wide
          label={m.autoInProgress}
          hint={m.autoInProgressHint}
          checked={form.autoInProgress}
          onChange={(v) => set("autoInProgress", v)}
        />
        <CheckSetting
          id={`${prefix}-autoDone`}
          wide
          label={m.autoDone}
          hint={m.autoDoneHint}
          checked={form.autoDone}
          onChange={(v) => set("autoDone", v)}
        />
      </FieldSection>

      <SettingsActions>
        <Button type="submit" className="h-9 max-md:h-11" disabled={save.isPending}>
          {save.isPending ? m.saving : m.save}
        </Button>
        {connection ? (
          <Button
            type="button"
            variant="destructive"
            className="h-9 max-md:h-11"
            disabled={remove.isPending}
            onClick={() => (confirmingDelete ? remove.mutate(connection.id) : setConfirmingDelete(true))}
            onBlur={() => setConfirmingDelete(false)}
          >
            {confirmingDelete ? m.deleteConfirm : m.delete}
          </Button>
        ) : (
          <Button type="button" variant="ghost" className="h-9 max-md:h-11" onClick={onDone}>
            {m.close}
          </Button>
        )}
      </SettingsActions>
    </form>
  );
}
