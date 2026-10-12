"use client";

import { useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation } from "@tanstack/react-query";
import { FieldGroup } from "@/components/ui/field";
import { ApiError } from "@/lib/api/errors";
import { messages } from "@/messages";
import { completeSetup } from "../api";
import { formErrorMessage } from "../error-message";
import { setupSchema, type SetupInput, type SetupValues } from "../schema";
import { FormAlert } from "./form-alert";
import { LoginIdField } from "./login-id-field";
import { PasswordField } from "./password-field";
import { SubmitButton } from "./submit-button";

const SERVER_FIELDS = ["loginId", "password"] as const;

export function SetupForm() {
  const router = useRouter();
  const [formError, setFormError] = useState<string | null>(null);
  const [alreadyCompleted, setAlreadyCompleted] = useState(false);
  const [created, setCreated] = useState(false);
  const form = useForm<SetupInput, unknown, SetupValues>({
    resolver: zodResolver(setupSchema),
    defaultValues: { loginId: "", password: "", passwordConfirm: "" },
  });
  const mutation = useMutation({ mutationFn: completeSetup });
  const { errors } = form.formState;

  const onSubmit = form.handleSubmit(async ({ loginId, password }) => {
    setFormError(null);
    setAlreadyCompleted(false);
    try {
      await mutation.mutateAsync({ loginId, password });
      setCreated(true);
      // The new account is already signed in.
      router.replace("/");
      router.refresh();
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
    }
  });

  return (
    <form onSubmit={onSubmit} noValidate className="flex flex-col gap-5">
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
      <FieldGroup>
        <LoginIdField
          id="loginId"
          registration={form.register("loginId")}
          error={errors.loginId?.message}
          hint={messages.setup.loginIdHint}
          autoFocus
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
      <SubmitButton
        pending={mutation.isPending || created}
        label={messages.setup.submit}
        pendingLabel={messages.setup.submitting}
      />
    </form>
  );
}
