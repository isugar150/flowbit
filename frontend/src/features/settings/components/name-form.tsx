"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { useMutation } from "@tanstack/react-query";
import { Button } from "@/components/ui/button";
import { Field, FieldDescription, FieldError, FieldLabel } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { Spinner } from "@/components/ui/spinner";
import { messages } from "@/messages";
import { settingsErrorMessage } from "../error-message";

const m = messages.settings;

/**
 * One text setting with its own save button. Used for the profile name and the workspace name,
 * which differ only in their limits and in what saving calls.
 */
export function NameForm({
  id,
  label,
  hint,
  initialValue,
  maxLength,
  required,
  messages: text,
  save,
}: {
  id: string;
  label: string;
  hint?: string;
  initialValue: string;
  maxLength: number;
  required: boolean;
  messages: { required?: string; tooLong: string };
  save: (value: string) => Promise<unknown>;
}) {
  const router = useRouter();
  const [value, setValue] = useState(initialValue);
  const [savedValue, setSavedValue] = useState(initialValue);
  const [problem, setProblem] = useState<string | null>(null);
  const mutation = useMutation({
    mutationFn: save,
    onSuccess: (_result, saved) => {
      setSavedValue(saved);
      setValue(saved);
      // Re-render server components (sidebar, user menu) with the new name.
      router.refresh();
    },
  });
  const trimmed = value.trim();
  const error = problem ?? (mutation.isError ? settingsErrorMessage(mutation.error) : null);
  const saved = mutation.isSuccess && trimmed === savedValue;

  const onSubmit = (event: React.FormEvent) => {
    event.preventDefault();
    mutation.reset();
    const found =
      required && !trimmed ? (text.required ?? null) : Array.from(trimmed).length > maxLength ? text.tooLong : null;
    setProblem(found);
    if (!found) mutation.mutate(trimmed);
  };

  return (
    <form onSubmit={onSubmit} noValidate>
      <Field data-invalid={error ? true : undefined}>
        <FieldLabel htmlFor={id}>{label}</FieldLabel>
        <div className="flex flex-col gap-2 sm:flex-row">
          <Input
            id={id}
            value={value}
            className="h-9 max-md:h-11 sm:max-w-80"
            autoComplete="off"
            aria-invalid={error ? true : undefined}
            aria-describedby={`${id}-status`}
            onChange={(event) => {
              setValue(event.target.value);
              setProblem(null);
            }}
          />
          <Button
            type="submit"
            variant="outline"
            className="h-9 max-md:h-11"
            disabled={mutation.isPending || trimmed === savedValue}
          >
            {mutation.isPending ? <Spinner data-icon="inline-start" aria-hidden="true" /> : null}
            {mutation.isPending ? m.saving : m.save}
          </Button>
        </div>
        <div id={`${id}-status`}>
          {error ? (
            <FieldError>{error}</FieldError>
          ) : saved ? (
            <FieldDescription role="status">{m.saved}</FieldDescription>
          ) : hint ? (
            <FieldDescription>{hint}</FieldDescription>
          ) : null}
        </div>
      </Field>
    </form>
  );
}
