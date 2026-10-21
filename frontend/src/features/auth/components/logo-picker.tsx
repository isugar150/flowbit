"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { ImageUpIcon, Trash2Icon } from "lucide-react";
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";
import { Button } from "@/components/ui/button";
import {
  Field,
  FieldDescription,
  FieldError,
  FieldTitle,
} from "@/components/ui/field";
import {
  ACCEPTED_IMAGE_TYPES,
  fileProblem,
} from "@/features/settings/error-message";
import { useUploadLimit } from "@/features/settings/upload-limit-provider";
import { messages } from "@/messages";

const m = messages.setup;
const ROUNDED = "rounded-xl after:rounded-xl";

/**
 * Chooses the workspace logo during setup. Nothing is uploaded here: nobody is signed in yet, so
 * the form sends the file once the administrator account exists.
 */
export function LogoPicker({
  id,
  file,
  fallback,
  disabled,
  onChange,
}: {
  id: string;
  file: File | null;
  /** Shown while no file is chosen. */
  fallback: React.ReactNode;
  disabled?: boolean;
  onChange: (file: File | null) => void;
}) {
  const limit = useUploadLimit();
  const inputRef = useRef<HTMLInputElement>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const previewUrl = useMemo(
    () => (file ? URL.createObjectURL(file) : null),
    [file],
  );
  useEffect(() => {
    if (!previewUrl) return;
    return () => URL.revokeObjectURL(previewUrl);
  }, [previewUrl]);

  const onFileChosen = (event: React.ChangeEvent<HTMLInputElement>) => {
    const chosen = event.target.files?.[0];
    // Allow choosing the same file again after removing it.
    event.target.value = "";
    if (!chosen) return;
    const found = fileProblem(chosen, limit.bytes);
    setProblem(found);
    if (!found) onChange(chosen);
  };

  return (
    <Field data-invalid={problem ? true : undefined}>
      <FieldTitle id={`${id}-label`}>{m.logoLabel}</FieldTitle>
      <div className="flex items-center gap-4">
        <Avatar className={`size-16 ${ROUNDED}`}>
          {previewUrl ? (
            <AvatarImage src={previewUrl} alt={m.logoAlt} className={ROUNDED} />
          ) : null}
          <AvatarFallback className={`text-base ${ROUNDED}`}>
            {fallback}
          </AvatarFallback>
        </Avatar>
        <div className="flex flex-wrap gap-2">
          <input
            ref={inputRef}
            id={id}
            type="file"
            accept={ACCEPTED_IMAGE_TYPES.join(",")}
            className="sr-only"
            tabIndex={-1}
            aria-labelledby={`${id}-label`}
            aria-describedby={`${id}-hint`}
            onChange={onFileChosen}
          />
          <Button
            type="button"
            variant="outline"
            className="max-md:h-11"
            disabled={disabled}
            aria-describedby={`${id}-hint`}
            onClick={() => inputRef.current?.click()}
          >
            <ImageUpIcon data-icon="inline-start" />
            {file ? m.logoReplace : m.logoChoose}
          </Button>
          {file ? (
            <Button
              type="button"
              variant="ghost"
              className="max-md:h-11"
              disabled={disabled}
              onClick={() => {
                setProblem(null);
                onChange(null);
              }}
            >
              <Trash2Icon data-icon="inline-start" />
              {m.logoRemove}
            </Button>
          ) : null}
        </div>
      </div>
      {problem ? <FieldError>{problem}</FieldError> : null}
      <FieldDescription id={`${id}-hint`}>{m.logoHint(limit.label)}</FieldDescription>
    </Field>
  );
}
