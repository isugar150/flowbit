"use client";

import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import type { z } from "zod";
import { Button } from "@/components/ui/button";
import { Field, FieldDescription, FieldError, FieldGroup, FieldLabel } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { Spinner } from "@/components/ui/spinner";
import { FormAlert } from "@/features/auth/components/form-alert";
import { apiErrorMessage } from "@/lib/api/error-message";
import { useRefreshingMutation } from "@/lib/use-refreshing-mutation";
import { messages } from "@/messages";
import { updateProject } from "../api";
import { projectNameSchema } from "../schema";
import type { Project } from "../types";

const m = messages.projects;
type Values = z.infer<typeof projectNameSchema>;

/**
 * The name of a project. The key is shown but can never be edited. The summary and the
 * description are not here: they are written directly on the project's overview.
 */
export function ProjectDetailsForm({ project, canManage }: { project: Project; canManage: boolean }) {
  const form = useForm<Values>({ resolver: zodResolver(projectNameSchema), defaultValues: { name: project.name } });
  const mutation = useRefreshingMutation(updateProject, (saved) => form.reset({ name: saved.name }));
  const { errors, isDirty } = form.formState;

  const onSubmit = form.handleSubmit((values) => mutation.mutate({ key: project.key, changes: values }));

  return (
    <form onSubmit={onSubmit} noValidate className="flex flex-col gap-5">
      {mutation.isError ? <FormAlert>{apiErrorMessage(mutation.error)}</FormAlert> : null}
      <FieldGroup>
        <Field data-invalid={errors.name ? true : undefined} data-disabled={canManage ? undefined : true}>
          <FieldLabel htmlFor="details-name">{m.nameLabel}</FieldLabel>
          <Input
            id="details-name"
            className="h-9 max-md:h-11 sm:max-w-96"
            autoComplete="off"
            disabled={!canManage}
            aria-invalid={errors.name ? true : undefined}
            {...form.register("name")}
          />
          {errors.name ? <FieldError>{errors.name.message}</FieldError> : null}
        </Field>
        <Field data-disabled>
          <FieldLabel htmlFor="details-key">{m.keyLabel}</FieldLabel>
          <Input id="details-key" className="h-9 w-40 tabular-nums max-md:h-11" value={project.key} disabled readOnly />
        </Field>
      </FieldGroup>
      {canManage ? (
        <div className="flex items-center gap-3">
          <Button type="submit" variant="outline" className="h-9 max-md:h-11" disabled={mutation.isPending || !isDirty}>
            {mutation.isPending ? <Spinner data-icon="inline-start" aria-hidden="true" /> : null}
            {mutation.isPending ? m.saving : m.save}
          </Button>
          {mutation.isSuccess && !isDirty ? <FieldDescription role="status">{m.saved}</FieldDescription> : null}
        </div>
      ) : (
        <FieldDescription>{m.readOnlyNote}</FieldDescription>
      )}
    </form>
  );
}
