"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import type { z } from "zod";
import { Button } from "@/components/ui/button";
import { DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Field, FieldDescription, FieldError, FieldGroup, FieldLabel } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { Spinner } from "@/components/ui/spinner";
import { Textarea } from "@/components/ui/textarea";
import { FormAlert } from "@/features/auth/components/form-alert";
import { apiErrorMessage } from "@/lib/api/error-message";
import { ApiError } from "@/lib/api/errors";
import { useRefreshingMutation } from "@/lib/use-refreshing-mutation";
import { messages } from "@/messages";
import { createProject } from "../api";
import { createProjectSchema } from "../schema";

const m = messages.projects;

type Input = z.input<typeof createProjectSchema>;
type Values = z.output<typeof createProjectSchema>;
const DEFAULTS: Input = { name: "", description: "" };

/** create-project-dialog.tsx looks for the same id when it opens. */
const PROJECT_NAME_FIELD = "project-name";

/**
 * What the new-project dialog holds. It lives only while the dialog is open, so every opening
 * starts empty, and it is loaded on first use (create-project-dialog.tsx).
 */
export function CreateProjectForm({ onClose }: { onClose: () => void }) {
  const router = useRouter();
  const [formError, setFormError] = useState<string | null>(null);
  const form = useForm<Input, unknown, Values>({ resolver: zodResolver(createProjectSchema), defaultValues: DEFAULTS });
  const mutation = useRefreshingMutation(createProject, (project) => {
    onClose();
    router.push(`/project/${project.id}`);
  });
  const { errors } = form.formState;

  // The first time, the dialog is already open when this arrives, after the dialog chose where
  // the cursor goes; put it in the name, which is what the dialog is for.
  useEffect(() => {
    document.getElementById(PROJECT_NAME_FIELD)?.focus();
  }, []);

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
      setFormError(apiErrorMessage(error));
    }
  });

  return (
    <form onSubmit={onSubmit} noValidate className="flex flex-col gap-5">
      <DialogHeader>
        <DialogTitle>{m.newTitle}</DialogTitle>
        <DialogDescription>{m.newDescription}</DialogDescription>
      </DialogHeader>
      {formError ? <FormAlert>{formError}</FormAlert> : null}
      <FieldGroup>
        <Field data-invalid={errors.name ? true : undefined}>
          <FieldLabel htmlFor={PROJECT_NAME_FIELD}>{m.nameLabel}</FieldLabel>
          <Input
            id={PROJECT_NAME_FIELD}
            className="h-10 max-md:h-11"
            placeholder={m.namePlaceholder}
            autoComplete="off"
            aria-invalid={errors.name ? true : undefined}
            {...form.register("name")}
          />
          {errors.name ? <FieldError>{errors.name.message}</FieldError> : null}
        </Field>
        <Field data-invalid={errors.description ? true : undefined}>
          <FieldLabel htmlFor="project-description">{m.descriptionLabel}</FieldLabel>
          <Textarea
            id="project-description"
            rows={3}
            aria-invalid={errors.description ? true : undefined}
            {...form.register("description")}
          />
          {errors.description ? (
            <FieldError>{errors.description.message}</FieldError>
          ) : (
            <FieldDescription>{m.descriptionHint}</FieldDescription>
          )}
        </Field>
      </FieldGroup>
      <DialogFooter>
        <Button type="button" variant="outline" className="max-md:h-11" onClick={onClose}>
          {m.cancel}
        </Button>
        <Button type="submit" className="max-md:h-11" disabled={mutation.isPending}>
          {mutation.isPending ? <Spinner data-icon="inline-start" aria-hidden="true" /> : null}
          {mutation.isPending ? m.creating : m.create}
        </Button>
      </DialogFooter>
    </form>
  );
}
