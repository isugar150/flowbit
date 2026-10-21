"use client";

import { useRef, useState } from "react";
import { FileIcon, PaperclipIcon, XIcon } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Spinner } from "@/components/ui/spinner";
import { MAX_UPLOAD_BYTES } from "@/features/settings/error-message";
import { apiErrorMessage } from "@/lib/api/error-message";
import { useRefreshingMutation } from "@/lib/use-refreshing-mutation";
import { messages } from "@/messages";
import { deleteAttachment, uploadAttachment } from "../api";
import type { Attachment } from "../types";

const m = messages.issues.attachments;

export function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(bytes < 10 * 1024 ? 1 : 0)} KB`;
  if (bytes < 1024 ** 3) return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
  return `${(bytes / 1024 ** 3).toFixed(2)} GB`;
}

/**
 * Files attached to an issue: any kind of file, kept as it was uploaded and always handed back as
 * a download. People who may edit the issue add and remove them; everyone who sees it can download.
 */
export function IssueAttachments({
  number,
  attachments,
  canEdit,
}: {
  number: number;
  attachments: Attachment[];
  canEdit: boolean;
}) {
  const inputRef = useRef<HTMLInputElement>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const upload = useRefreshingMutation(uploadAttachment);
  const remove = useRefreshingMutation(deleteAttachment);
  const failure = upload.error ?? remove.error;
  const error = problem ?? (failure ? apiErrorMessage(failure) : null);

  const onFilesChosen = async (event: React.ChangeEvent<HTMLInputElement>) => {
    const files = Array.from(event.target.files ?? []);
    event.target.value = "";
    upload.reset();
    remove.reset();
    setProblem(null);
    for (const file of files) {
      if (file.size > MAX_UPLOAD_BYTES) {
        setProblem(m.tooLarge(file.name));
        continue;
      }
      try {
        await upload.mutateAsync({ number, file });
      } catch {
        // Shown through upload.error; the remaining files are not tried.
        return;
      }
    }
  };

  if (attachments.length === 0 && !canEdit) return null;

  return (
    <section data-presence-id="attachments" aria-label={m.heading} className="flex flex-col gap-2">
      {attachments.length > 0 ? (
        <ul className="flex flex-col gap-1.5">
          {attachments.map((attachment) => (
            <li key={attachment.id} className="flex items-center gap-3 rounded-lg border bg-card px-3 py-2">
              <FileIcon className="size-5 shrink-0 text-muted-foreground" aria-hidden="true" />
              <a
                href={attachment.url}
                download={attachment.fileName}
                className="flex min-w-0 flex-1 flex-col rounded-sm outline-none focus-visible:ring-3 focus-visible:ring-ring/50"
              >
                <span className="truncate font-medium">{attachment.fileName}</span>
                <span className="text-xs leading-4 text-muted-foreground tabular-nums">{formatSize(attachment.sizeBytes)}</span>
              </a>
              {canEdit ? (
                <Button
                  type="button"
                  variant="ghost"
                  size="icon-sm"
                  className="text-muted-foreground max-md:size-11"
                  aria-label={m.remove(attachment.fileName)}
                  title={m.remove(attachment.fileName)}
                  disabled={remove.isPending}
                  onClick={() => {
                    setProblem(null);
                    upload.reset();
                    remove.mutate(attachment.id);
                  }}
                >
                  <XIcon />
                </Button>
              ) : null}
            </li>
          ))}
        </ul>
      ) : null}
      {canEdit ? (
        <div>
          <input ref={inputRef} type="file" multiple hidden tabIndex={-1} aria-hidden="true" onChange={onFilesChosen} />
          <Button
            type="button"
            variant="ghost"
            size="sm"
            className="-ml-2 text-muted-foreground max-md:h-11"
            disabled={upload.isPending}
            onClick={() => inputRef.current?.click()}
          >
            {upload.isPending ? <Spinner data-icon="inline-start" aria-hidden="true" /> : <PaperclipIcon data-icon="inline-start" />}
            {upload.isPending ? m.uploading : m.add}
          </Button>
        </div>
      ) : null}
      {error ? (
        <p role="alert" className="text-[0.8125rem] leading-5 text-destructive">
          {error}
        </p>
      ) : null}
    </section>
  );
}
