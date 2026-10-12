"use client";

import { useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useForm, useWatch } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation } from "@tanstack/react-query";
import { Button } from "@/components/ui/button";
import { Field, FieldDescription, FieldError, FieldGroup, FieldLabel, FieldLegend, FieldSet } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { uploadWorkspaceLogo } from "@/features/settings/api";
import { settingsErrorMessage } from "@/features/settings/error-message";
import { ApiError } from "@/lib/api/errors";
import { initials } from "@/lib/initials";
import { messages } from "@/messages";
import { completeSetup } from "../api";
import { formErrorMessage } from "../error-message";
import { setupSchema, type SetupInput, type SetupValues } from "../schema";
import { FormAlert } from "./form-alert";
import { LoginIdField } from "./login-id-field";
import { LogoPicker } from "./logo-picker";
import { PasswordField } from "./password-field";
import { SubmitButton } from "./submit-button";

const SERVER_FIELDS = ["workspaceName", "loginId", "password"] as const;

export function SetupForm() {
  const router = useRouter();
  const [formError, setFormError] = useState<string | null>(null);
  const [alreadyCompleted, setAlreadyCompleted] = useState(false);
  const [created, setCreated] = useState(false);
  const [logo, setLogo] = useState<File | null>(null);
  // Setup succeeds only once, so a logo that fails afterwards cannot be retried from this form.
  const [logoError, setLogoError] = useState<string | null>(null);
  const form = useForm<SetupInput, unknown, SetupValues>({
    resolver: zodResolver(setupSchema),
    defaultValues: { workspaceName: "", loginId: "", password: "", passwordConfirm: "" },
  });
  const mutation = useMutation({ mutationFn: completeSetup });
  const { errors } = form.formState;
  const workspaceName = useWatch({ control: form.control, name: "workspaceName" });
  const pending = mutation.isPending || created;

  const enter = (path: string) => {
    router.replace(path);
    router.refresh();
  };

  const onSubmit = form.handleSubmit(async ({ workspaceName, loginId, password }) => {
    setFormError(null);
    setAlreadyCompleted(false);
    try {
      await mutation.mutateAsync({ workspaceName, loginId, password });
    } catch (error) {
      if (error instanceof ApiError) {
        // The server applies the same rules; show what it rejected under the field it names.
        let focused = false;
        for (const fieldError of error.fieldErrors) {
          const field = SERVER_FIELDS.find((name) => name === fieldError.field);
          if (!field) continue;
          form.setError(field, { message: fieldError.message }, { shouldFocus: !focused });
          focused = true;
        }
        setAlreadyCompleted(error.code === "SETUP_ALREADY_COMPLETED");
      }
      setFormError(formErrorMessage(error));
      return;
    }
    setCreated(true);
    // The new account is already signed in, which is what lets it upload the logo.
    if (logo) {
      try {
        await uploadWorkspaceLogo(logo);
      } catch (error) {
        setLogoError(settingsErrorMessage(error));
        return;
      }
    }
    enter("/");
  });

  if (logoError) {
    return (
      <div className="flex flex-col gap-5">
        <FormAlert>
          {messages.setup.logoFailed} {logoError}
        </FormAlert>
        <div className="flex flex-col gap-2">
          <Button type="button" className="h-10 max-md:h-11" onClick={() => enter("/admin/workspace")}>
            {messages.setup.logoRetry}
          </Button>
          <Button type="button" variant="ghost" className="h-10 max-md:h-11" onClick={() => enter("/")}>
            {messages.setup.logoSkip}
          </Button>
        </div>
      </div>
    );
  }

  return (
    // method="post": if the browser submits the form itself (its script has not loaded), the
    // password goes in the request body, never in the address.
    <form method="post" onSubmit={onSubmit} noValidate className="flex flex-col gap-7">
      {formError ? (
        <FormAlert>
          {formError}
          {alreadyCompleted ? (
            <>
              {" "}
              <Link href="/login" className="font-medium underline underline-offset-4">
                {messages.setup.goToLogin}
              </Link>
            </>
          ) : null}
        </FormAlert>
      ) : null}
      <FieldSet>
        <FieldLegend>{messages.setup.workspaceSection}</FieldLegend>
        <FieldGroup>
          <Field data-invalid={errors.workspaceName ? true : undefined}>
            <FieldLabel htmlFor="workspaceName">{messages.setup.workspaceNameLabel}</FieldLabel>
            <Input
              id="workspaceName"
              type="text"
              className="h-10 max-md:h-11"
              autoComplete="organization"
              maxLength={100}
              // The first field of a page whose only purpose is this form.
              autoFocus
              aria-invalid={errors.workspaceName ? true : undefined}
              aria-describedby={errors.workspaceName ? "workspaceName-error" : "workspaceName-hint"}
              {...form.register("workspaceName")}
            />
            {errors.workspaceName ? (
              <FieldError id="workspaceName-error">{errors.workspaceName.message}</FieldError>
            ) : (
              <FieldDescription id="workspaceName-hint">{messages.setup.workspaceNameHint}</FieldDescription>
            )}
          </Field>
          <LogoPicker
            id="logo"
            file={logo}
            fallback={initials(workspaceName.trim() || "Flowbit")}
            disabled={pending}
            onChange={setLogo}
          />
        </FieldGroup>
      </FieldSet>
      <FieldSet>
        <FieldLegend>{messages.setup.accountSection}</FieldLegend>
        <FieldGroup>
          <LoginIdField
            id="loginId"
            registration={form.register("loginId")}
            error={errors.loginId?.message}
            hint={messages.setup.loginIdHint}
          />
          <PasswordField
            id="password"
            label={messages.fields.password}
            autoComplete="new-password"
            registration={form.register("password")}
            error={errors.password?.message}
            hint={messages.setup.passwordHint}
          />
          <PasswordField
            id="passwordConfirm"
            label={messages.fields.passwordConfirm}
            autoComplete="new-password"
            registration={form.register("passwordConfirm")}
            error={errors.passwordConfirm?.message}
          />
        </FieldGroup>
      </FieldSet>
      <SubmitButton pending={pending} label={messages.setup.submit} pendingLabel={messages.setup.submitting} />
    </form>
  );
}
