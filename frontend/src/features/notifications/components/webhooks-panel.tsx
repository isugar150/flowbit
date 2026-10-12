"use client";

import { useState } from "react";
import { useMutation } from "@tanstack/react-query";
import { PlusIcon } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { FieldDescription } from "@/components/ui/field";
import {
  AdvancedSettings,
  CheckSetting,
  FieldSection,
  SelectSetting,
  TextAreaSetting,
  TextSetting,
} from "@/components/setting-fields";
import { SettingsActions } from "@/components/settings-card";
import { FormAlert } from "@/features/auth/components/form-alert";
import { apiErrorMessage, fieldErrorsOf } from "@/lib/api/error-message";
import { useRefreshingMutation } from "@/lib/use-refreshing-mutation";
import { messages } from "@/messages";
import {
  createWebhook,
  deleteWebhook,
  testWebhook,
  updateWebhook,
} from "../api";
import {
  WEBHOOK_EVENT_TYPES,
  type Webhook,
  type WebhookEventType,
  type WebhookInput,
  type WebhookMethod,
} from "../types";
import { SaveButton, TestButton, TestOutcome } from "./channel-settings-forms";

const n = messages.notifications;
const w = n.webhooks;

type Form = {
  name: string;
  enabled: boolean;
  url: string;
  method: WebhookMethod;
  contentType: string;
  /** One "Name: value" per line. */
  headers: string;
  bodyTemplate: string;
  signingSecret: string;
  eventTypes: WebhookEventType[];
  perRecipient: boolean;
};

const EMPTY: Form = {
  name: "",
  enabled: true,
  url: "",
  method: "POST",
  contentType: "application/json",
  headers: "",
  bodyTemplate: "",
  signingSecret: "",
  eventTypes: [],
  perRecipient: false,
};

const METHODS: WebhookMethod[] = ["POST", "GET", "PUT", "PATCH", "DELETE"];
const JSON_BODY = {
  method: "POST",
  contentType: "application/json",
  perRecipient: false,
} as const;

/** Starting points for services people commonly point a webhook at. The first is Flowbit's own JSON. */
const PRESETS: { label: string; values: Partial<Form> }[] = [
  { label: w.presetStandard, values: { ...JSON_BODY, bodyTemplate: "" } },
  {
    label: w.presetSlack,
    values: { ...JSON_BODY, bodyTemplate: '{"text": "{{text}}"}' },
  },
  {
    label: w.presetDiscord,
    values: { ...JSON_BODY, bodyTemplate: '{"content": "{{text}}"}' },
  },
  {
    label: w.presetTeams,
    values: {
      ...JSON_BODY,
      bodyTemplate:
        '{"@type": "MessageCard", "@context": "https://schema.org/extensions", "summary": "{{title}}", "title": "{{title}}", "text": "{{headline}}\\n\\n{{changes}}"}',
    },
  },
  {
    // One NAVER WORKS message per subscriber through the company's hrm API. The bot id and the
    // token are filled in by hand.
    label: w.presetHrmWorks,
    values: {
      method: "GET",
      perRecipient: true,
      bodyTemplate: "",
      url: "https://hrm.motherk.link/api/works/send?botId=봇ID&email={{recipient.email}}&message={{text}}",
      headers: "Authorization: Bearer 토큰",
    },
  },
];

function formOf(webhook: Webhook): Form {
  return {
    name: webhook.name,
    enabled: webhook.enabled,
    url: webhook.url,
    method: webhook.method,
    contentType: webhook.contentType,
    headers: Object.entries(webhook.headers)
      .map(([name, value]) => `${name}: ${value}`)
      .join("\n"),
    bodyTemplate: webhook.bodyTemplate,
    signingSecret: "",
    eventTypes: webhook.eventTypes,
    perRecipient: webhook.perRecipient,
  };
}

function inputOf(form: Form, id?: string): WebhookInput {
  const headers: Record<string, string> = {};
  for (const line of form.headers.split("\n")) {
    const colon = line.indexOf(":");
    if (colon > 0)
      headers[line.slice(0, colon).trim()] = line.slice(colon + 1).trim();
  }
  // An empty secret box means "keep what is stored".
  return { ...form, headers, signingSecret: form.signingSecret || null, id };
}

/** Every outgoing API call as a row that opens to its form, and one more form to add another. */
export function WebhooksPanel({ webhooks }: { webhooks: Webhook[] }) {
  const [open, setOpen] = useState<string | null>(null);

  return (
    <div className="flex flex-col gap-4">
      {webhooks.length === 0 && open !== "new" ? (
        <p className="text-muted-foreground">{w.empty}</p>
      ) : null}
      {webhooks.length > 0 ? (
        <ul className="flex flex-col divide-y rounded-lg border">
          {webhooks.map((webhook) => (
            <li key={webhook.id} className="flex flex-col gap-5 px-4 py-3">
              <div className="flex flex-wrap items-center justify-between gap-x-4 gap-y-2">
                <div className="flex min-w-0 flex-col">
                  <span className="flex items-center gap-2">
                    <span className="truncate font-medium">{webhook.name}</span>
                    {webhook.enabled ? null : (
                      <Badge variant="outline">{w.disabled}</Badge>
                    )}
                  </span>
                  <span className="truncate text-[0.8125rem] leading-5 text-muted-foreground">
                    {webhook.method} {webhook.url}
                  </span>
                  <span className="text-[0.8125rem] leading-5 text-muted-foreground">
                    {lastResult(webhook)}
                  </span>
                </div>
                <Button
                  variant="ghost"
                  size="sm"
                  className="max-md:h-11"
                  aria-expanded={open === webhook.id}
                  onClick={() =>
                    setOpen(open === webhook.id ? null : webhook.id)
                  }
                >
                  {open === webhook.id ? w.close : w.edit}
                </Button>
              </div>
              {open === webhook.id ? (
                <WebhookForm webhook={webhook} onDone={() => setOpen(null)} />
              ) : null}
            </li>
          ))}
        </ul>
      ) : null}
      {open === "new" ? (
        <div className="flex flex-col gap-5 rounded-lg border px-4 py-4">
          <h4 className="font-medium">{w.newTitle}</h4>
          <WebhookForm onDone={() => setOpen(null)} />
        </div>
      ) : (
        <div>
          <Button
            variant="outline"
            className="h-9 max-md:h-11"
            onClick={() => setOpen("new")}
          >
            <PlusIcon data-icon="inline-start" />
            {w.add}
          </Button>
        </div>
      )}
    </div>
  );
}

function lastResult(webhook: Webhook): string {
  if (!webhook.lastAttemptAt) return w.neverCalled;
  if (
    webhook.lastStatus !== null &&
    webhook.lastStatus >= 200 &&
    webhook.lastStatus < 300
  ) {
    return w.lastOk(webhook.lastStatus);
  }
  return w.lastFailed(webhook.lastError ?? "");
}

function WebhookForm({
  webhook,
  onDone,
}: {
  webhook?: Webhook;
  onDone: () => void;
}) {
  const prefix = `webhook-${webhook?.id ?? "new"}`;
  const [form, setForm] = useState<Form>(webhook ? formOf(webhook) : EMPTY);
  const [confirmingDelete, setConfirmingDelete] = useState(false);
  const save = useRefreshingMutation(
    (input: WebhookInput) =>
      webhook ? updateWebhook({ id: webhook.id, input }) : createWebhook(input),
    onDone,
  );
  const remove = useRefreshingMutation(deleteWebhook, onDone);
  const test = useMutation({ mutationFn: testWebhook });
  const errors = { ...fieldErrorsOf(test.error), ...fieldErrorsOf(save.error) };
  const change = (values: Partial<Form>) => {
    setForm((current) => ({ ...current, ...values }));
    save.reset();
    test.reset();
  };
  const set = <K extends keyof Form>(name: K, value: Form[K]) =>
    change({ [name]: value } as Partial<Form>);
  const toggleEvent = (type: WebhookEventType, on: boolean) =>
    set(
      "eventTypes",
      on
        ? [...form.eventTypes, type]
        : form.eventTypes.filter((other) => other !== type),
    );
  // GET and DELETE carry no body: everything they say is in the address.
  const bodiless = form.method === "GET" || form.method === "DELETE";
  const failure = [save, remove].find(
    (mutation) =>
      mutation.isError &&
      Object.keys(fieldErrorsOf(mutation.error)).length === 0,
  );

  return (
    <form
      noValidate
      className="flex flex-col gap-7"
      onSubmit={(event) => {
        event.preventDefault();
        save.mutate(inputOf(form));
      }}
    >
      {failure ? <FormAlert>{apiErrorMessage(failure.error)}</FormAlert> : null}

      <FieldSection title={n.sections.target}>
        <div className="flex flex-wrap items-center gap-1.5 sm:col-span-2">
          <span className="text-[0.8125rem] text-muted-foreground">
            {w.presets}
          </span>
          {PRESETS.map((preset) => (
            <Button
              key={preset.label}
              type="button"
              variant="outline"
              size="sm"
              className="max-md:h-11"
              onClick={() => change(preset.values)}
            >
              {preset.label}
            </Button>
          ))}
        </div>
        <TextSetting
          id={`${prefix}-name`}
          label={w.name}
          value={form.name}
          error={errors.name}
          onChange={(v) => set("name", v)}
        />
        <SelectSetting
          id={`${prefix}-method`}
          label={w.method}
          value={form.method}
          options={METHODS.map((value) => ({ value, label: value }))}
          onChange={(v) => set("method", v)}
        />
        <TextSetting
          id={`${prefix}-url`}
          wide
          label={w.url}
          hint={w.urlHint}
          value={form.url}
          error={errors.url}
          onChange={(v) => set("url", v)}
        />
        <TextAreaSetting
          id={`${prefix}-headers`}
          wide
          label={w.headers}
          hint={w.headersHint}
          rows={2}
          value={form.headers}
          error={errors.headers}
          onChange={(v) => set("headers", v)}
        />
      </FieldSection>

      <FieldSection title={n.sections.content}>
        {bodiless ? (
          <FieldDescription className="sm:col-span-2">
            {w.noBody}
          </FieldDescription>
        ) : (
          <TextAreaSetting
            id={`${prefix}-bodyTemplate`}
            wide
            label={w.bodyTemplate}
            hint={w.bodyTemplateHint}
            rows={5}
            value={form.bodyTemplate}
            error={errors.bodyTemplate}
            onChange={(v) => set("bodyTemplate", v)}
          />
        )}
      </FieldSection>

      <FieldSection title={n.sections.when}>
        <CheckSetting
          id={`${prefix}-enabled`}
          wide
          label={w.enabled}
          checked={form.enabled}
          onChange={(v) => set("enabled", v)}
        />
        <CheckSetting
          id={`${prefix}-perRecipient`}
          wide
          label={w.perRecipient}
          hint={w.perRecipientHint}
          checked={form.perRecipient}
          onChange={(v) => set("perRecipient", v)}
        />
        <fieldset className="flex flex-col gap-2 sm:col-span-2">
          <legend className="mb-1.5 text-sm font-medium">{w.eventTypes}</legend>
          <div className="grid grid-cols-2 gap-x-4 gap-y-1 sm:grid-cols-3">
            {WEBHOOK_EVENT_TYPES.map((type) => (
              <CheckSetting
                key={type}
                id={`${prefix}-event-${type}`}
                label={w.events[type]}
                checked={form.eventTypes.includes(type)}
                onChange={(on) => toggleEvent(type, on)}
              />
            ))}
          </div>
          <FieldDescription>
            {errors.eventTypes ?? w.eventTypesHint}
          </FieldDescription>
        </fieldset>
      </FieldSection>

      {bodiless ? null : (
        <AdvancedSettings label={n.advanced}>
          <FieldSection title={w.signingSecret}>
            <TextSetting
              id={`${prefix}-contentType`}
              label={w.contentType}
              value={form.contentType}
              error={errors.contentType}
              onChange={(v) => set("contentType", v)}
            />
            <TextSetting
              id={`${prefix}-signingSecret`}
              type="password"
              label={w.signingSecret}
              hint={
                webhook?.signingSecretSet
                  ? w.signingSecretStored
                  : w.signingSecretHint
              }
              value={form.signingSecret}
              onChange={(v) => set("signingSecret", v)}
            />
          </FieldSection>
        </AdvancedSettings>
      )}

      <TestOutcome
        result={test.data}
        error={
          test.isError && Object.keys(fieldErrorsOf(test.error)).length === 0
            ? test.error
            : null
        }
      />
      <SettingsActions>
        <SaveButton pending={save.isPending} />
        <TestButton
          pending={test.isPending}
          onClick={() => test.mutate(inputOf(form, webhook?.id))}
        />
        {webhook ? (
          <Button
            type="button"
            variant="destructive"
            className="h-9 max-md:h-11"
            disabled={remove.isPending}
            onClick={() =>
              confirmingDelete
                ? remove.mutate(webhook.id)
                : setConfirmingDelete(true)
            }
            onBlur={() => setConfirmingDelete(false)}
          >
            {confirmingDelete ? w.deleteConfirm : w.delete}
          </Button>
        ) : (
          <Button
            type="button"
            variant="ghost"
            className="h-9 max-md:h-11"
            onClick={onDone}
          >
            {w.close}
          </Button>
        )}
      </SettingsActions>
    </form>
  );
}
