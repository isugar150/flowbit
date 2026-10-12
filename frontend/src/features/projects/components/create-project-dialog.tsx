"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { PlusIcon } from "lucide-react";
import type { z } from "zod";
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
import { Spinner } from "@/components/ui/spinner";
import { Textarea } from "@/components/ui/textarea";
import { FormAlert } from "@/features/auth/components/form-alert";
import { apiErrorMessage } from "@/lib/api/error-message";
import { ApiError } from "@/lib/api/errors";
import { useRefreshingMutation } from "@/lib/use-refreshing-mutation";
import { messages } from "@/messages";
import { createProject } from "../api";
import { createProjectSchema, suggestProjectKey } from "../schema";

const m = messages.projects;

type Input = z.input<typeof createProjectSchema>;
type Values = z.output<typeof createProjectSchema>;
const DEFAULTS: Input = { name: "", key: "", description: "" };

/** `variant="icon"` is the small plus in the sidebar; `"button"` is the labelled button on pages. */
export function CreateProjectDialog({ variant }: { variant: "icon" | "button" }) {
  const router = useRouter();
  const [open, setOpen] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);
  const form = useForm<Input, unknown, Values>({ resolver: zodResolver(createProjectSchema), defaultValues: DEFAULTS });
  const mutation = useRefreshingMutation(createProject, (project) => {
    setOpen(false);
    router.push(`/project/${project.key}`);
  });
  const { errors, dirtyFields } = form.formState;

  const onOpenChange = (next: boolean) => {
    setOpen(next);
    if (next) {
      form.reset(DEFAULTS);
      setFormError(null);
      mutation.reset();
    }
  };

  const nameField = form.register("name", {
    // Offer a key from the name until the person edits the key themselves.
    onChange: (event) => {
      if (!dirtyFields.key) form.setValue("key", suggestProjectKey(event.target.value));
    },
  });

  const onSubmit = form.handleSubmit(async (values) => {
    setFormError(null);
    try {
      await mutation.mutateAsync(values);
    } catch (error) {
      if (error instanceof ApiError && error.code === "PROJECT_KEY_TAKEN") {
        form.setError("key", { message: error.message }, { shouldFocus: true });
        return;
      }
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
    <Dialog open={open} onOpenChange={onOpenChange}>
      {variant === "icon" ? (
        <DialogTrigger render={<Button variant="ghost" size="icon-sm" aria-label={m.new} />}>
          <PlusIcon />
        </DialogTrigger>
      ) : (
        <DialogTrigger render={<Button className="max-md:h-11" />}>
          <PlusIcon data-icon="inline-start" />
          {m.new}
        </DialogTrigger>
      )}
      <DialogContent initialFocus={() => document.getElementById("project-name")}>
        <form onSubmit={onSubmit} noValidate className="flex flex-col gap-5">
          <DialogHeader>
            <DialogTitle>{m.newTitle}</DialogTitle>
            <DialogDescription>{m.newDescription}</DialogDescription>
          </DialogHeader>
          {formError ? <FormAlert>{formError}</FormAlert> : null}
          <FieldGroup>
            <Field data-invalid={errors.name ? true : undefined}>
              <FieldLabel htmlFor="project-name">{m.nameLabel}</FieldLabel>
              <Input
                id="project-name"
                className="h-10 max-md:h-11"
                placeholder={m.namePlaceholder}
                autoComplete="off"
                aria-invalid={errors.name ? true : undefined}
                {...nameField}
              />
              {errors.name ? <FieldError>{errors.name.message}</FieldError> : null}
            </Field>
            <Field data-invalid={errors.key ? true : undefined}>
              <FieldLabel htmlFor="project-key">{m.keyLabel}</FieldLabel>
              <Input
                id="project-key"
                className="h-10 w-40 uppercase tabular-nums max-md:h-11"
                autoComplete="off"
                autoCapitalize="characters"
                spellCheck={false}
                maxLength={10}
                aria-invalid={errors.key ? true : undefined}
                aria-describedby="project-key-hint"
                {...form.register("key")}
              />
              {errors.key ? <FieldError>{errors.key.message}</FieldError> : null}
              <FieldDescription id="project-key-hint">{m.keyHint}</FieldDescription>
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
