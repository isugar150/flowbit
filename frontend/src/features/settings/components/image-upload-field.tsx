"use client";

import { useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { useMutation } from "@tanstack/react-query";
import { ImageUpIcon, Trash2Icon } from "lucide-react";
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";
import { Button } from "@/components/ui/button";
import { Field, FieldDescription, FieldError, FieldTitle } from "@/components/ui/field";
import { Spinner } from "@/components/ui/spinner";
import { cn } from "@/lib/utils";
import { messages } from "@/messages";
import { ACCEPTED_IMAGE_TYPES, fileProblem, settingsErrorMessage } from "../error-message";
import { useUploadLimit } from "../upload-limit-provider";

const m = messages.settings.image;

/**
 * Shows the current image with buttons to replace or remove it. The upload is sent as soon as a
 * file is chosen; the server converts it to WebP and resizes it, so what comes back is what is stored.
 */
export function ImageUploadField({
  id,
  label,
  imageUrl,
  imageAlt,
  fallback,
  shape,
  upload,
  remove,
}: {
  id: string;
  label: string;
  imageUrl: string | null;
  imageAlt: string;
  /** Shown when there is no image: initials for a person, a mark for the workspace. */
  fallback: React.ReactNode;
  shape: "circle" | "square";
  upload: (file: File) => Promise<unknown>;
  remove: () => Promise<unknown>;
}) {
  const router = useRouter();
  const limit = useUploadLimit();
  const inputRef = useRef<HTMLInputElement>(null);
  const [problem, setProblem] = useState<string | null>(null);
  // Re-render server components (sidebar, user menu) with the new image.
  const refresh = () => router.refresh();
  const uploadMutation = useMutation({ mutationFn: upload, onSuccess: refresh });
  const removeMutation = useMutation({ mutationFn: remove, onSuccess: refresh });
  const busy = uploadMutation.isPending || removeMutation.isPending;
  const failure = uploadMutation.error ?? removeMutation.error;
  const error = problem ?? (failure ? settingsErrorMessage(failure) : null);
  const rounded = shape === "circle" ? "rounded-full after:rounded-full" : "rounded-xl after:rounded-xl";

  const onFileChosen = (event: React.ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    // Allow choosing the same file again after a failure.
    event.target.value = "";
    if (!file) return;
    uploadMutation.reset();
    removeMutation.reset();
    const found = fileProblem(file, limit.bytes);
    setProblem(found);
    if (!found) uploadMutation.mutate(file);
  };

  const onRemove = () => {
    setProblem(null);
    uploadMutation.reset();
    removeMutation.mutate();
  };

  return (
    <Field data-invalid={error ? true : undefined}>
      <FieldTitle id={`${id}-label`}>{label}</FieldTitle>
      <div className="flex items-center gap-4">
        <Avatar className={cn("size-16", rounded)}>
          {imageUrl ? <AvatarImage src={imageUrl} alt={imageAlt} className={rounded} /> : null}
          <AvatarFallback className={cn("text-base", rounded)}>{fallback}</AvatarFallback>
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
            disabled={busy}
            aria-describedby={`${id}-hint`}
            onClick={() => inputRef.current?.click()}
          >
            {uploadMutation.isPending ? (
              <Spinner data-icon="inline-start" aria-hidden="true" />
            ) : (
              <ImageUpIcon data-icon="inline-start" />
            )}
            {uploadMutation.isPending ? m.uploading : imageUrl ? m.replace : m.upload}
          </Button>
          {imageUrl ? (
            <Button type="button" variant="ghost" className="max-md:h-11" disabled={busy} onClick={onRemove}>
              {removeMutation.isPending ? (
                <Spinner data-icon="inline-start" aria-hidden="true" />
              ) : (
                <Trash2Icon data-icon="inline-start" />
              )}
              {removeMutation.isPending ? m.removing : m.remove}
            </Button>
          ) : null}
        </div>
      </div>
      {error ? <FieldError>{error}</FieldError> : null}
      <FieldDescription id={`${id}-hint`}>{m.hint(limit.label)}</FieldDescription>
    </Field>
  );
}
