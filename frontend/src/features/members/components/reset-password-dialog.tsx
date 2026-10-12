"use client";

import { useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog";
import { Spinner } from "@/components/ui/spinner";
import { FormAlert } from "@/features/auth/components/form-alert";
import { PasswordField } from "@/features/auth/components/password-field";
import { newPassword } from "@/features/auth/schema";
import { apiErrorMessage, fieldErrorsOf } from "@/lib/api/error-message";
import { useRefreshingMutation } from "@/lib/use-refreshing-mutation";
import { messages } from "@/messages";
import { resetMemberPassword } from "../api";

const m = messages.members;
const schema = z.object({ password: newPassword });
type Values = z.infer<typeof schema>;

export function ResetPasswordDialog({
  memberId,
  memberName,
}: {
  memberId: string;
  memberName: string;
}) {
  const [open, setOpen] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);
  const form = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: { password: "" },
  });
  const mutation = useRefreshingMutation(resetMemberPassword, () =>
    setOpen(false),
  );
  const fieldId = `reset-password-${memberId}`;

  const onOpenChange = (next: boolean) => {
    setOpen(next);
    if (next) {
      form.reset({ password: "" });
      setFormError(null);
      mutation.reset();
    }
  };

  const onSubmit = form.handleSubmit(async ({ password }) => {
    setFormError(null);
    try {
      await mutation.mutateAsync({ id: memberId, password });
    } catch (error) {
      const passwordError = fieldErrorsOf(error).password;
      if (passwordError)
        form.setError(
          "password",
          { message: passwordError },
          { shouldFocus: true },
        );
      else setFormError(apiErrorMessage(error));
    }
  });

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogTrigger
        render={<Button variant="ghost" size="sm" className="max-md:h-11" />}
      >
        {m.resetPassword}
      </DialogTrigger>
      <DialogContent initialFocus={() => document.getElementById(fieldId)}>
        <form onSubmit={onSubmit} noValidate className="flex flex-col gap-5">
          <DialogHeader>
            <DialogTitle>{m.resetTitle(memberName)}</DialogTitle>
            <DialogDescription>{m.resetDescription}</DialogDescription>
          </DialogHeader>
          {formError ? <FormAlert>{formError}</FormAlert> : null}
          <PasswordField
            id={fieldId}
            label={m.newPasswordLabel}
            autoComplete="new-password"
            registration={form.register("password")}
            error={form.formState.errors.password?.message}
            hint={messages.setup.passwordHint}
          />
          <DialogFooter>
            <Button
              type="button"
              variant="outline"
              className="max-md:h-11"
              onClick={() => setOpen(false)}
            >
              {m.cancel}
            </Button>
            <Button
              type="submit"
              className="max-md:h-11"
              disabled={mutation.isPending}
            >
              {mutation.isPending ? (
                <Spinner data-icon="inline-start" aria-hidden="true" />
              ) : null}
              {mutation.isPending ? m.resetting : m.reset}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
