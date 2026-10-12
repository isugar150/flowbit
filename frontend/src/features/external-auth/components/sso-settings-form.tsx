"use client";

import { useState } from "react";
import { useMutation } from "@tanstack/react-query";
import { Button } from "@/components/ui/button";
import { FieldDescription } from "@/components/ui/field";
import { Spinner } from "@/components/ui/spinner";
import {
  AdvancedSettings,
  CheckSetting,
  FieldSection,
  SegmentedSetting,
  SelectSetting,
  TextAreaSetting,
  TextSetting,
} from "@/components/setting-fields";
import { SettingsActions } from "@/components/settings-card";
import { FormAlert } from "@/features/auth/components/form-alert";
import { apiErrorMessage, fieldErrorsOf } from "@/lib/api/error-message";
import { messages } from "@/messages";
import { saveSsoSettings } from "../api";
import type { SsoSettings } from "../types";

const m = messages.externalAuth;
const s = m.sso;

type Form = Omit<SsoSettings, "clientSecretSet" | "redirectUri"> & {
  clientSecret: string;
};

/** hrm: NAVER WORKS sign-in through the company's own identity service, which is not OpenID Connect. */
const HRM_PRESET: Partial<Form> = {
  protocol: "OAUTH2",
  displayName: "NAVER WORKS",
  buttonStyle: "NAVER_WORKS",
  authorizeUrl: "https://hrm.motherk.link/api/sso/authorize",
  tokenUrl: "https://hrm.motherk.link/api/sso/token",
  userinfoUrl: "",
  tokenAuth: "BEARER",
  tokenFormat: "JSON",
  tokenBody: '{"clientId": "{{clientId}}", "code": "{{code}}"}',
  scopes: "",
  subjectField: "email",
  loginIdClaim: "email",
  emailField: "email",
  nameField: "userName",
};

function options<T extends string>(
  labels: Record<T, string>,
): { value: T; label: string }[] {
  return (Object.keys(labels) as T[]).map((value) => ({
    value,
    label: labels[value],
  }));
}

export function SsoSettingsForm({
  settings,
  onSaved,
}: {
  settings: SsoSettings;
  onSaved?: (saved: SsoSettings) => void;
}) {
  const { clientSecretSet, redirectUri, ...initial } = settings;
  const [form, setForm] = useState<Form>({ ...initial, clientSecret: "" });
  const [secretStored, setSecretStored] = useState(clientSecretSet);
  const save = useMutation({
    mutationFn: saveSsoSettings,
    onSuccess: (saved) => {
      setSecretStored(saved.clientSecretSet);
      setForm((current) => ({ ...current, clientSecret: "" }));
      onSaved?.(saved);
    },
  });
  const errors = fieldErrorsOf(save.error);
  const set = <K extends keyof Form>(name: K, value: Form[K]) => {
    setForm((current) => ({ ...current, [name]: value }));
    save.reset();
  };
  const oidc = form.protocol === "OIDC";
  const text = (
    name: keyof Form & string,
    label: string,
    extra: { hint?: string; wide?: boolean; type?: "url" | "password" } = {},
  ) => (
    <TextSetting
      id={`sso-${name}`}
      label={label}
      value={String(form[name])}
      error={errors[name]}
      onChange={(v) => set(name, v as never)}
      {...extra}
    />
  );

  return (
    <form
      noValidate
      className="flex flex-col gap-7"
      onSubmit={(event) => {
        event.preventDefault();
        // An empty secret box means "keep what is stored".
        save.mutate({ ...form, clientSecret: form.clientSecret || null });
      }}
    >
      {save.isError && Object.keys(errors).length === 0 ? (
        <FormAlert>{apiErrorMessage(save.error)}</FormAlert>
      ) : null}
      <CheckSetting
        id="sso-enabled"
        label={s.enabled}
        checked={form.enabled}
        onChange={(v) => set("enabled", v)}
      />

      <FieldSection title={m.sections.provider}>
        <SegmentedSetting
          label={s.protocol}
          value={form.protocol}
          options={[
            {
              value: "OIDC",
              label: s.protocolOidc,
              description: s.protocolOidcHint,
            },
            {
              value: "OAUTH2",
              label: s.protocolOauth2,
              description: s.protocolOauth2Hint,
            },
          ]}
          onChange={(v) => set("protocol", v)}
        />
        {oidc ? null : (
          <div className="flex flex-wrap items-center gap-1.5 sm:col-span-2">
            <span className="text-[0.8125rem] text-muted-foreground">
              {s.presets}
            </span>
            <Button
              type="button"
              variant="outline"
              size="sm"
              className="max-md:h-11"
              onClick={() => {
                setForm((current) => ({ ...current, ...HRM_PRESET }));
                save.reset();
              }}
            >
              {s.presetHrm}
            </Button>
          </div>
        )}
        <TextSetting
          id="sso-redirectUri"
          wide
          label={s.redirectUri}
          hint={s.redirectUriHint}
          value={redirectUri}
          readOnly
        />
        {oidc ? (
          text("issuer", s.issuer, {
            hint: s.issuerHint,
            wide: true,
            type: "url",
          })
        ) : (
          <>
            {text("authorizeUrl", s.authorizeUrl, {
              hint: s.authorizeUrlHint,
              wide: true,
              type: "url",
            })}
            {text("tokenUrl", s.tokenUrl, {
              hint: s.tokenUrlHint,
              wide: true,
              type: "url",
            })}
          </>
        )}
        {text("clientId", s.clientId)}
        <TextSetting
          id="sso-clientSecret"
          type="password"
          label={s.clientSecret}
          hint={secretStored ? s.clientSecretStored : undefined}
          value={form.clientSecret}
          error={errors.clientSecret}
          onChange={(v) => set("clientSecret", v)}
        />
      </FieldSection>

      {oidc ? null : (
        <FieldSection title={m.sections.exchange}>
          <SelectSetting
            id="sso-tokenAuth"
            label={s.tokenAuth}
            value={form.tokenAuth}
            options={options(s.tokenAuthOptions)}
            onChange={(v) => set("tokenAuth", v)}
          />
          <SelectSetting
            id="sso-tokenFormat"
            label={s.tokenFormat}
            value={form.tokenFormat}
            options={options(s.tokenFormatOptions)}
            onChange={(v) => set("tokenFormat", v)}
          />
          <TextAreaSetting
            id="sso-tokenBody"
            wide
            rows={2}
            label={s.tokenBody}
            hint={s.tokenBodyHint}
            value={form.tokenBody}
            error={errors.tokenBody}
            onChange={(v) => set("tokenBody", v)}
          />
          {text("userinfoUrl", s.userinfoUrl, {
            hint: s.userinfoUrlHint,
            wide: true,
            type: "url",
          })}
        </FieldSection>
      )}

      <FieldSection
        title={m.sections.mapping}
        description={oidc ? undefined : s.fieldPathHint}
      >
        {oidc
          ? null
          : text("subjectField", s.subjectField, { hint: s.subjectFieldHint })}
        {text("loginIdClaim", s.loginIdClaim, {
          hint: s.loginIdClaimHint,
          wide: oidc,
        })}
        {oidc ? null : (
          <>
            {text("emailField", s.emailField)}
            {text("nameField", s.nameField)}
          </>
        )}
      </FieldSection>

      <FieldSection title={m.sections.button}>
        {text("displayName", s.displayName, { hint: s.displayNameHint })}
        <SelectSetting
          id="sso-buttonStyle"
          label={s.buttonStyle}
          value={form.buttonStyle}
          options={options(s.buttonStyleOptions)}
          onChange={(v) => set("buttonStyle", v)}
        />
      </FieldSection>

      <FieldSection title={m.sections.signup}>
        <CheckSetting
          id="sso-autoProvision"
          wide
          label={m.autoProvision}
          hint={m.autoProvisionHint}
          checked={form.autoProvision}
          onChange={(v) => set("autoProvision", v)}
        />
      </FieldSection>

      <AdvancedSettings label={m.advanced}>
        <FieldSection title={s.scopes}>
          {text("scopes", s.scopes, { wide: true })}
        </FieldSection>
      </AdvancedSettings>

      <SettingsActions>
        <Button
          type="submit"
          className="h-9 max-md:h-11"
          disabled={save.isPending}
        >
          {save.isPending ? (
            <Spinner data-icon="inline-start" aria-hidden="true" />
          ) : null}
          {save.isPending ? m.saving : m.save}
        </Button>
        {save.isSuccess ? (
          <FieldDescription role="status">{m.saved}</FieldDescription>
        ) : null}
      </SettingsActions>
    </form>
  );
}
