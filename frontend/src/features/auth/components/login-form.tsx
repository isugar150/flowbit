"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation } from "@tanstack/react-query";
import { FieldGroup } from "@/components/ui/field";
import { safeNextPath } from "@/lib/safe-next-path";
import { messages } from "@/messages";
import { login } from "../api";
import { formErrorMessage } from "../error-message";
import { loginSchema, type LoginInput, type LoginValues } from "../schema";
import { FormAlert } from "./form-alert";
import { LoginIdField } from "./login-id-field";
import { PasswordField } from "./password-field";
import { SubmitButton } from "./submit-button";

export function LoginForm() {
  const router = useRouter();
  const [formError, setFormError] = useState<string | null>(null);
  const [signedIn, setSignedIn] = useState(false);
  const form = useForm<LoginInput, unknown, LoginValues>({
    resolver: zodResolver(loginSchema),
    defaultValues: { loginId: "", password: "" },
  });
  const mutation = useMutation({ mutationFn: login });
  const { errors } = form.formState;

  const onSubmit = form.handleSubmit(async (values) => {
    setFormError(null);
    try {
      await mutation.mutateAsync(values);
      setSignedIn(true);
      router.replace(safeNextPath(new URLSearchParams(window.location.search).get("next")));
      // Drop anything the router cached while signed out.
      router.refresh();
    } catch (error) {
      setFormError(formErrorMessage(error));
      form.resetField("password");
      form.setFocus("password");
    }
  });

  return (
    <form onSubmit={onSubmit} noValidate className="flex flex-col gap-5">
      {formError ? <FormAlert>{formError}</FormAlert> : null}
      <FieldGroup>
        <LoginIdField id="loginId" registration={form.register("loginId")} error={errors.loginId?.message} autoFocus />
        <PasswordField
          id="password"
          label={messages.fields.password}
          autoComplete="current-password"
          registration={form.register("password")}
          error={errors.password?.message}
        />
      </FieldGroup>
      <SubmitButton
        pending={mutation.isPending || signedIn}
        label={messages.login.submit}
        pendingLabel={messages.login.submitting}
      />
    </form>
  );
}
