"use client";

import { useState } from "react";
import { useMutation } from "@tanstack/react-query";
import { Button } from "@/components/ui/button";
import { FieldDescription } from "@/components/ui/field";
import { Spinner } from "@/components/ui/spinner";
import {
  CheckSetting,
  FieldSection,
  TextSetting,
} from "@/components/setting-fields";
import { SettingsActions } from "@/components/settings-card";
import { FormAlert } from "@/features/auth/components/form-alert";
import { apiErrorMessage, fieldErrorsOf } from "@/lib/api/error-message";
import { messages } from "@/messages";
import { saveLdapSettings, testLdapSettings } from "../api";
import type { LdapSettings, LdapSettingsInput } from "../types";

const m = messages.externalAuth;
const l = m.ldap;

type Form = Omit<LdapSettings, "bindPasswordSet"> & {
  bindPassword: string;
  testLoginId: string;
};

export function LdapSettingsForm({
  settings,
  onSaved,
}: {
  settings: LdapSettings;
  onSaved?: (saved: LdapSettings) => void;
}) {
  const [form, setForm] = useState<Form>({
    ...settings,
    bindPassword: "",
    testLoginId: "",
  });
  const [passwordStored, setPasswordStored] = useState(
    settings.bindPasswordSet,
  );
  const save = useMutation({
    mutationFn: saveLdapSettings,
    onSuccess: (saved) => {
      setPasswordStored(saved.bindPasswordSet);
      setForm((current) => ({ ...current, bindPassword: "" }));
      onSaved?.(saved);
    },
  });
  const test = useMutation({ mutationFn: testLdapSettings });
  const errors = fieldErrorsOf(save.error);
  const set = <K extends keyof Form>(name: K, value: Form[K]) => {
    setForm((current) => ({ ...current, [name]: value }));
    save.reset();
    test.reset();
  };
  // An empty password box means "keep what is stored", never "clear it".
  const input = (): LdapSettingsInput => {
    const { testLoginId, bindPassword, ...rest } = form;
    return {
      ...rest,
      bindPassword: bindPassword || null,
      testLoginId: testLoginId || null,
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
      {save.isError && Object.keys(errors).length === 0 ? (
        <FormAlert>{apiErrorMessage(save.error)}</FormAlert>
      ) : null}
      <CheckSetting
        id="ldap-enabled"
        label={l.enabled}
        checked={form.enabled}
        onChange={(v) => set("enabled", v)}
      />

      <FieldSection title={m.sections.connection}>
        <TextSetting
          id="ldap-url"
          wide
          label={l.url}
          hint={l.urlHint}
          value={form.url}
          error={errors.url}
          onChange={(v) => set("url", v)}
        />
        <CheckSetting
          id="ldap-startTls"
          wide
          label={l.startTls}
          checked={form.startTls}
          error={errors.startTls}
          onChange={(v) => set("startTls", v)}
        />
        <TextSetting
          id="ldap-bindDn"
          label={l.bindDn}
          hint={l.bindDnHint}
          value={form.bindDn}
          onChange={(v) => set("bindDn", v)}
        />
        <TextSetting
          id="ldap-bindPassword"
          type="password"
          label={l.bindPassword}
          hint={passwordStored ? l.bindPasswordStored : undefined}
          value={form.bindPassword}
          onChange={(v) => set("bindPassword", v)}
        />
      </FieldSection>

      <FieldSection title={m.sections.search} description={l.searchDescription}>
        <TextSetting
          id="ldap-baseDn"
          wide
          label={l.baseDn}
          hint={l.baseDnHint}
          value={form.baseDn}
          error={errors.baseDn}
          onChange={(v) => set("baseDn", v)}
        />
        <TextSetting
          id="ldap-userFilter"
          wide
          label={l.userFilter}
          hint={l.userFilterHint}
          value={form.userFilter}
          error={errors.userFilter}
          onChange={(v) => set("userFilter", v)}
        />
      </FieldSection>

      <FieldSection
        title={m.sections.mapping}
        description={l.mappingDescription}
      >
        <TextSetting
          id="ldap-loginIdAttribute"
          wide
          label={l.loginIdAttribute}
          hint={l.loginIdAttributeHint}
          value={form.loginIdAttribute}
          error={errors.loginIdAttribute}
          onChange={(v) => set("loginIdAttribute", v)}
        />
        <TextSetting
          id="ldap-emailAttribute"
          label={l.emailAttribute}
          value={form.emailAttribute}
          error={errors.emailAttribute}
          onChange={(v) => set("emailAttribute", v)}
        />
        <TextSetting
          id="ldap-displayNameAttribute"
          label={l.displayNameAttribute}
          value={form.displayNameAttribute}
          error={errors.displayNameAttribute}
          onChange={(v) => set("displayNameAttribute", v)}
        />
      </FieldSection>

      <FieldSection title={m.sections.signup}>
        <CheckSetting
          id="ldap-autoProvision"
          wide
          label={m.autoProvision}
          hint={m.autoProvisionHint}
          checked={form.autoProvision}
          onChange={(v) => set("autoProvision", v)}
        />
      </FieldSection>

      <FieldSection title={m.sections.test}>
        <TextSetting
          id="ldap-testLoginId"
          wide
          label={l.testLoginId}
          hint={l.testLoginIdHint}
          value={form.testLoginId}
          onChange={(v) => set("testLoginId", v)}
        />
      </FieldSection>

      {test.isError ? (
        <FormAlert>{apiErrorMessage(test.error)}</FormAlert>
      ) : null}
      {test.data && !test.data.ok ? (
        <FormAlert>{test.data.message}</FormAlert>
      ) : null}
      {test.data?.ok ? (
        <p
          role="status"
          className="rounded-lg border px-3 py-2 text-[0.8125rem] leading-5"
        >
          {test.data.message}
          {test.data.loginId ? (
            <span className="block text-muted-foreground">
              {l.testFound(
                test.data.loginId,
                test.data.displayName ?? "",
                test.data.email ?? "",
              )}
            </span>
          ) : null}
        </p>
      ) : null}

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
        <Button
          type="button"
          variant="outline"
          className="h-9 max-md:h-11"
          disabled={test.isPending}
          onClick={() => test.mutate(input())}
        >
          {test.isPending ? (
            <Spinner data-icon="inline-start" aria-hidden="true" />
          ) : null}
          {test.isPending ? l.testing : l.test}
        </Button>
        {save.isSuccess ? (
          <FieldDescription role="status">{m.saved}</FieldDescription>
        ) : null}
      </SettingsActions>
    </form>
  );
}
