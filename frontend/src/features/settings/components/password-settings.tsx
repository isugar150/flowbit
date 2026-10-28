"use client";

import { useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation } from "@tanstack/react-query";
import { z } from "zod";
import { Button } from "@/components/ui/button";
import { FieldDescription } from "@/components/ui/field";
import { Spinner } from "@/components/ui/spinner";
import { FormAlert } from "@/features/auth/components/form-alert";
import { PasswordField } from "@/features/auth/components/password-field";
import { newPassword } from "@/features/auth/schema";
import { apiErrorMessage, fieldErrorsOf } from "@/lib/api/error-message";
import { messages } from "@/messages";
import { changePassword } from "../api";

const m = messages.settings.password;
const v = messages.validation;

const schema = z
  .object({
    currentPassword: z.string().min(1, m.currentRequired),
    newPassword,
    newPasswordConfirm: z.string().min(1, v.passwordConfirmRequired),
  })
  .superRefine((value, context) => {
    if (value.newPasswordConfirm && value.newPassword !== value.newPasswordConfirm) {
      context.addIssue({ code: "custom", path: ["newPasswordConfirm"], message: v.passwordMismatch });
    }
  });
type Values = z.infer<typeof schema>;

const EMPTY: Values = { currentPassword: "", newPassword: "", newPasswordConfirm: "" };

/** Changing one's own password. Only shown while LDAP is off and the account keeps its password here. */
export function PasswordSettings() {
  const [formError, setFormError] = useState<string | null>(null);
  const form = useForm<Values>({ resolver: zodResolver(schema), defaultValues: EMPTY });
  const mutation = useMutation({ mutationFn: changePassword });

  const onSubmit = form.handleSubmit(async ({ currentPassword, newPassword: next }) => {
    setFormError(null);
    mutation.reset();
    try {
      await mutation.mutateAsync({ currentPassword, newPassword: next });
      form.reset(EMPTY);
    } catch (error) {
      const fields = fieldErrorsOf(error);
      if (fields.currentPassword) {
        form.setError("currentPassword", { message: fields.currentPassword }, { shouldFocus: true });
      } else if (fields.newPassword) {
        form.setError("newPassword", { message: fields.newPassword }, { shouldFocus: true });
      } else {
        setFormError(apiErrorMessage(error));
      }
    }
  });

  return (
    <form onSubmit={onSubmit} noValidate className="flex max-w-80 flex-col gap-4">
      {formError ? <FormAlert>{formError}</FormAlert> : null}
      <PasswordField
        id="current-password"
        label={m.currentLabel}
        autoComplete="current-password"
        registration={form.register("currentPassword")}
        error={form.formState.errors.currentPassword?.message}
      />
      <PasswordField
        id="new-password"
        label={m.newLabel}
        autoComplete="new-password"
        registration={form.register("newPassword")}
        error={form.formState.errors.newPassword?.message}
        hint={messages.setup.passwordHint}
      />
      <PasswordField
        id="new-password-confirm"
        label={m.confirmLabel}
        autoComplete="new-password"
        registration={form.register("newPasswordConfirm")}
        error={form.formState.errors.newPasswordConfirm?.message}
      />
      <div className="flex flex-col gap-2">
        <Button type="submit" variant="outline" className="h-9 self-start max-md:h-11" disabled={mutation.isPending}>
          {mutation.isPending ? <Spinner data-icon="inline-start" aria-hidden="true" /> : null}
          {mutation.isPending ? m.submitting : m.submit}
        </Button>
        {mutation.isSuccess ? <FieldDescription role="status">{m.changed}</FieldDescription> : null}
      </div>
    </form>
  );
}
