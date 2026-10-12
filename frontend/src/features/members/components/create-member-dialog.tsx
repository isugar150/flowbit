"use client";

import { useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { UserPlusIcon } from "lucide-react";
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
import { Field, FieldDescription, FieldError, FieldGroup, FieldLabel } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { NativeSelect, NativeSelectOption } from "@/components/ui/native-select";
import { Spinner } from "@/components/ui/spinner";
import { FormAlert } from "@/features/auth/components/form-alert";
import { LoginIdField } from "@/features/auth/components/login-id-field";
import { PasswordField } from "@/features/auth/components/password-field";
import { newLoginId, newPassword } from "@/features/auth/schema";
import { apiErrorMessage } from "@/lib/api/error-message";
import { ApiError } from "@/lib/api/errors";
import { useRefreshingMutation } from "@/lib/use-refreshing-mutation";
import { messages } from "@/messages";
import { createMember } from "../api";

const m = messages.members;

const schema = z.object({
  loginId: newLoginId,
  displayName: z.string().trim().max(50, messages.settings.profile.nameTooLong),
  password: newPassword,
  role: z.enum(["MEMBER", "ADMIN"]),
});

type Input = z.input<typeof schema>;
type Values = z.output<typeof schema>;
const DEFAULTS: Input = { loginId: "", displayName: "", password: "", role: "MEMBER" };

export function CreateMemberDialog() {
  const [open, setOpen] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);
  const form = useForm<Input, unknown, Values>({ resolver: zodResolver(schema), defaultValues: DEFAULTS });
  const mutation = useRefreshingMutation(createMember, () => setOpen(false));
  const { errors } = form.formState;

  const onOpenChange = (next: boolean) => {
    setOpen(next);
    if (next) {
      form.reset(DEFAULTS);
      setFormError(null);
      mutation.reset();
    }
  };

  const onSubmit = form.handleSubmit(async (values) => {
    setFormError(null);
    try {
      await mutation.mutateAsync(values);
    } catch (error) {
      if (error instanceof ApiError && error.fieldErrors.length > 0) {
        for (const fieldError of error.fieldErrors) {
          if (fieldError.field in DEFAULTS) {
            form.setError(fieldError.field as keyof Input, { message: fieldError.message });
          }
        }
        return;
      }
      if (error instanceof ApiError && error.code === "LOGIN_ID_TAKEN") {
        form.setError("loginId", { message: error.message }, { shouldFocus: true });
        return;
      }
      setFormError(apiErrorMessage(error));
    }
  });

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogTrigger render={<Button className="max-md:h-11" />}>
        <UserPlusIcon data-icon="inline-start" />
        {m.add}
      </DialogTrigger>
      <DialogContent initialFocus={() => document.getElementById("member-loginId")}>
        <form onSubmit={onSubmit} noValidate className="flex flex-col gap-5">
          <DialogHeader>
            <DialogTitle>{m.addTitle}</DialogTitle>
            <DialogDescription>{m.addDescription}</DialogDescription>
          </DialogHeader>
          {formError ? <FormAlert>{formError}</FormAlert> : null}
          <FieldGroup>
            <LoginIdField
              id="member-loginId"
              registration={form.register("loginId")}
              error={errors.loginId?.message}
              hint={messages.setup.loginIdHint}
            />
            <Field data-invalid={errors.displayName ? true : undefined}>
              <FieldLabel htmlFor="member-displayName">{m.nameLabel}</FieldLabel>
              <Input
                id="member-displayName"
                className="h-10 max-md:h-11"
                autoComplete="off"
                aria-invalid={errors.displayName ? true : undefined}
                {...form.register("displayName")}
              />
              {errors.displayName ? (
                <FieldError>{errors.displayName.message}</FieldError>
              ) : (
                <FieldDescription>{m.nameHint}</FieldDescription>
              )}
            </Field>
            <PasswordField
              id="member-password"
              label={m.passwordLabel}
              autoComplete="new-password"
              registration={form.register("password")}
              error={errors.password?.message}
              hint={messages.setup.passwordHint}
            />
            <Field>
              <FieldLabel htmlFor="member-role">{m.roleLabel}</FieldLabel>
              <NativeSelect id="member-role" className="h-10 max-md:h-11" {...form.register("role")}>
                <NativeSelectOption value="MEMBER">{messages.roles.MEMBER}</NativeSelectOption>
                <NativeSelectOption value="ADMIN">{messages.roles.ADMIN}</NativeSelectOption>
              </NativeSelect>
            </Field>
          </FieldGroup>
          <DialogFooter>
            <Button type="button" variant="outline" className="max-md:h-11" onClick={() => setOpen(false)}>
              {m.cancel}
            </Button>
            <Button type="submit" className="max-md:h-11" disabled={mutation.isPending}>
              {mutation.isPending ? <Spinner data-icon="inline-start" aria-hidden="true" /> : null}
              {mutation.isPending ? m.creating : m.create}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
