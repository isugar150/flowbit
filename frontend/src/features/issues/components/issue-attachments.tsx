"use client";

import { FileIcon, XIcon } from "lucide-react";
import { Button } from "@/components/ui/button";
import { apiErrorMessage } from "@/lib/api/error-message";
import { useRefreshingMutation } from "@/lib/use-refreshing-mutation";
import { messages } from "@/messages";
import { deleteAttachment } from "../api";
import type { Attachment } from "../types";

const m = messages.issues.attachments;

export function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(bytes < 10 * 1024 ? 1 : 0)} KB`;
  if (bytes < 1024 ** 3) return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
  return `${(bytes / 1024 ** 3).toFixed(2)} GB`;
}

/**
 * The files attached to an issue before files became blocks of the text (D-182): kept as they were uploaded and always
 * handed back as a download. New files go into the text itself (a `/파일` block, or a file dropped on it); this list only
 * shows the ones already attached, so they stay reachable. People who may edit the issue can still remove them.
 */
export function IssueAttachments({ attachments, canEdit }: { attachments: Attachment[]; canEdit: boolean }) {
  const remove = useRefreshingMutation(deleteAttachment);
  const error = remove.error ? apiErrorMessage(remove.error) : null;

  if (attachments.length === 0) return null;

  return (
    <section data-presence-id="attachments" aria-label={m.heading} className="flex flex-col gap-2">
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
                  remove.mutate(attachment.id);
                }}
              >
                <XIcon />
              </Button>
            ) : null}
          </li>
        ))}
      </ul>
      {error ? (
        <p role="alert" className="text-[0.8125rem] leading-5 text-destructive">
          {error}
        </p>
      ) : null}
    </section>
  );
}
