"use client";

import { useState } from "react";
import { useMutation } from "@tanstack/react-query";
import { Button } from "@/components/ui/button";
import { FieldDescription } from "@/components/ui/field";
import { Spinner } from "@/components/ui/spinner";
import {
  CheckSetting,
  FieldSection,
  SelectSetting,
  TextAreaSetting,
  TextSetting,
} from "@/components/setting-fields";
import { SettingsActions } from "@/components/settings-card";
import { FormAlert } from "@/features/auth/components/form-alert";
import { apiErrorMessage, fieldErrorsOf } from "@/lib/api/error-message";
import { messages } from "@/messages";
import { savePush, saveSmtp, testPush, testSmtp } from "../api";
import type {
  PushSettings,
  SmtpInput,
  SmtpSecurity,
  SmtpSettings,
  TestResult,
} from "../types";

const n = messages.notifications;

/** The outcome of a "test" button: a message from the server, or why the request itself failed. */
export function TestOutcome({
  result,
  error,
}: {
  result?: TestResult;
  error: unknown;
}) {
  if (error) return <FormAlert>{apiErrorMessage(error)}</FormAlert>;
  if (!result) return null;
  if (!result.ok) return <FormAlert>{result.message}</FormAlert>;
  return (
    <p
      role="status"
      className="rounded-lg border px-3 py-2 text-[0.8125rem] leading-5"
    >
      {result.message}
    </p>
  );
}

export function SaveButton({ pending }: { pending: boolean }) {
  return (
    <Button type="submit" className="h-9 max-md:h-11" disabled={pending}>
      {pending ? <Spinner data-icon="inline-start" aria-hidden="true" /> : null}
      {pending ? n.saving : n.save}
    </Button>
  );
}

export function TestButton({
  pending,
  onClick,
}: {
  pending: boolean;
  onClick: () => void;
}) {
  return (
    <Button
      type="button"
      variant="outline"
      className="h-9 max-md:h-11"
      disabled={pending}
      onClick={onClick}
    >
      {pending ? <Spinner data-icon="inline-start" aria-hidden="true" /> : null}
      {pending ? n.testing : n.test}
    </Button>
  );
}

/** Whether a failed request is something other than messages for individual fields. */
function isGeneralError(error: unknown): boolean {
  return Boolean(error) && Object.keys(fieldErrorsOf(error)).length === 0;
}

type SmtpForm = Omit<SmtpSettings, "passwordSet" | "port"> & {
  port: string;
  password: string;
  testTo: string;
};

export function SmtpSettingsForm({
  settings,
  onSaved,
}: {
  settings: SmtpSettings;
  onSaved?: (enabled: boolean) => void;
}) {
  const s = n.smtp;
  const [form, setForm] = useState<SmtpForm>({
    ...settings,
    port: String(settings.port),
    password: "",
    testTo: "",
  });
  const [passwordStored, setPasswordStored] = useState(settings.passwordSet);
  const save = useMutation({
    mutationFn: saveSmtp,
    onSuccess: (saved) => {
      setPasswordStored(saved.smtp.passwordSet);
      setForm((current) => ({ ...current, password: "" }));
      onSaved?.(saved.smtp.enabled);
    },
  });
  const test = useMutation({ mutationFn: testSmtp });
  const errors = { ...fieldErrorsOf(save.error), ...fieldErrorsOf(test.error) };
  const set = <K extends keyof SmtpForm>(name: K, value: SmtpForm[K]) => {
    setForm((current) => ({ ...current, [name]: value }));
    save.reset();
    test.reset();
  };
  // An empty password box means "keep what is stored".
  const input = (): SmtpInput => {
    const { testTo, password, port, ...rest } = form;
    return {
      ...rest,
      port: Number(port) || 0,
      password: password || null,
      testTo: testTo || null,
    };
  };

  return (
    <form
      noValidate
      className="flex flex-col gap-7"
      onSubmit={(event) => {
        event.preventDefault();
        test.reset();
        save.mutate(input());
      }}
    >
      {isGeneralError(save.error) ? (
        <FormAlert>{apiErrorMessage(save.error)}</FormAlert>
      ) : null}
      <CheckSetting
        id="smtp-enabled"
        label={s.enabled}
        checked={form.enabled}
        onChange={(v) => set("enabled", v)}
      />

      <FieldSection title={n.sections.server}>
        <TextSetting
          id="smtp-host"
          label={s.host}
          value={form.host}
          error={errors.host}
          onChange={(v) => set("host", v)}
        />
        <TextSetting
          id="smtp-port"
          label={s.port}
          value={form.port}
          error={errors.port}
          onChange={(v) => set("port", v)}
        />
        <SelectSetting
          id="smtp-security"
          wide
          label={s.security}
          value={form.security}
          options={(Object.keys(s.securityOptions) as SmtpSecurity[]).map(
            (value) => ({
              value,
              label: s.securityOptions[value],
            }),
          )}
          onChange={(v) => set("security", v)}
        />
        <TextSetting
          id="smtp-username"
          label={s.username}
          hint={s.usernameHint}
          value={form.username}
          onChange={(v) => set("username", v)}
        />
        <TextSetting
          id="smtp-password"
          type="password"
          label={s.password}
          hint={passwordStored ? s.passwordStored : undefined}
          value={form.password}
          onChange={(v) => set("password", v)}
        />
      </FieldSection>

      <FieldSection title={n.sections.sender}>
        <TextSetting
          id="smtp-fromAddress"
          label={s.fromAddress}
          value={form.fromAddress}
          error={errors.fromAddress}
          onChange={(v) => set("fromAddress", v)}
        />
        <TextSetting
          id="smtp-fromName"
          label={s.fromName}
          value={form.fromName}
          error={errors.fromName}
          onChange={(v) => set("fromName", v)}
        />
      </FieldSection>

      <FieldSection title={n.sections.test}>
        <TextSetting
          id="smtp-testTo"
          wide
          label={s.testTo}
          hint={s.testToHint}
          value={form.testTo}
          error={errors.testTo}
          onChange={(v) => set("testTo", v)}
        />
      </FieldSection>

      <TestOutcome
        result={test.data}
        error={isGeneralError(test.error) ? test.error : null}
      />
      <SettingsActions>
        <SaveButton pending={save.isPending} />
        <TestButton
          pending={test.isPending}
          onClick={() => test.mutate(input())}
        />
        {save.isSuccess ? (
          <FieldDescription role="status">{n.saved}</FieldDescription>
        ) : null}
      </SettingsActions>
    </form>
  );
}

type PushForm = Omit<PushSettings, "serviceAccountSet" | "projectId"> & {
  serviceAccount: string;
};

export function PushSettingsForm({
  settings,
  onSaved,
}: {
  settings: PushSettings;
  onSaved?: (enabled: boolean) => void;
}) {
  const p = n.push;
  const { serviceAccountSet, projectId, ...initial } = settings;
  const [form, setForm] = useState<PushForm>({
    ...initial,
    serviceAccount: "",
  });
  const [stored, setStored] = useState(
    serviceAccountSet ? (projectId ?? "") : null,
  );
  const save = useMutation({
    mutationFn: savePush,
    onSuccess: (saved) => {
      setStored(
        saved.push.serviceAccountSet ? (saved.push.projectId ?? "") : null,
      );
      setForm((current) => ({ ...current, serviceAccount: "" }));
      onSaved?.(saved.push.enabled);
    },
  });
  const test = useMutation({ mutationFn: testPush });
  const errors = fieldErrorsOf(save.error);
  const set = <K extends keyof PushForm>(name: K, value: PushForm[K]) => {
    setForm((current) => ({ ...current, [name]: value }));
    save.reset();
    test.reset();
  };

  return (
    <form
      noValidate
      className="flex flex-col gap-7"
      onSubmit={(event) => {
        event.preventDefault();
        test.reset();
        // An empty key box means "keep what is stored".
        save.mutate({
          ...form,
          serviceAccount: form.serviceAccount.trim() || null,
        });
      }}
    >
      {isGeneralError(save.error) ? (
        <FormAlert>{apiErrorMessage(save.error)}</FormAlert>
      ) : null}
      <CheckSetting
        id="push-enabled"
        label={p.enabled}
        checked={form.enabled}
        onChange={(v) => set("enabled", v)}
      />

      <FieldSection
        title={n.sections.server_key}
        description={p.serverKeyDescription}
      >
        <TextAreaSetting
          id="push-serviceAccount"
          wide
          label={p.serviceAccount}
          hint={
            stored !== null
              ? p.serviceAccountStored(stored)
              : p.serviceAccountHint
          }
          value={form.serviceAccount}
          error={errors.serviceAccount}
          onChange={(v) => set("serviceAccount", v)}
        />
      </FieldSection>

      <FieldSection title={n.sections.webApp} description={p.webConfigHint}>
        <TextSetting
          id="push-apiKey"
          wide
          label={p.apiKey}
          value={form.apiKey}
          error={errors.apiKey}
          onChange={(v) => set("apiKey", v)}
        />
        <TextSetting
          id="push-messagingSenderId"
          label={p.messagingSenderId}
          value={form.messagingSenderId}
          error={errors.messagingSenderId}
          onChange={(v) => set("messagingSenderId", v)}
        />
        <TextSetting
          id="push-appId"
          label={p.appId}
          value={form.appId}
          error={errors.appId}
          onChange={(v) => set("appId", v)}
        />
        <TextSetting
          id="push-vapidKey"
          wide
          label={p.vapidKey}
          hint={p.vapidKeyHint}
          value={form.vapidKey}
          error={errors.vapidKey}
          onChange={(v) => set("vapidKey", v)}
        />
      </FieldSection>

      <TestOutcome
        result={test.data}
        error={test.isError ? test.error : null}
      />
      <SettingsActions>
        <SaveButton pending={save.isPending} />
        <TestButton pending={test.isPending} onClick={() => test.mutate()} />
        <FieldDescription role={save.isSuccess ? "status" : undefined}>
          {save.isSuccess ? n.saved : p.testHint}
        </FieldDescription>
      </SettingsActions>
    </form>
  );
}
