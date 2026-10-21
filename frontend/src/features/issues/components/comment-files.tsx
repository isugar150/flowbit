"use client";

import { useRef, useState } from "react";
import { FileIcon, PaperclipIcon, XIcon } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Spinner } from "@/components/ui/spinner";
import { ACCEPTED_IMAGE_TYPES, MAX_UPLOAD_BYTES } from "@/features/settings/error-message";
import { apiErrorMessage } from "@/lib/api/error-message";
import { cn } from "@/lib/utils";
import { messages } from "@/messages";
import type { CommentApi } from "../comment-api";
import { MAX_COMMENT_FILES, type Attachment } from "../types";
import { formatSize } from "./issue-attachments";

const m = messages.issues.comments;

/** A file of a comment being written: on its way up (no `attachment` yet), or uploaded. */
export type DraftFile = { key: string; name: string; sizeBytes: number; attachment: Attachment | null };

const draftOf = (attachment: Attachment): DraftFile => ({
  key: attachment.id,
  name: attachment.fileName,
  sizeBytes: attachment.sizeBytes,
  attachment,
});

/** Pictures go into the text of the comment; every other file is attached to it. */
export function splitFiles(list: File[]): { pictures: File[]; others: File[] } {
  const pictures = list.filter((file) => ACCEPTED_IMAGE_TYPES.includes(file.type));
  return { pictures, others: list.filter((file) => !pictures.includes(file)) };
}

/**
 * The files of a comment being written. Each is uploaded the moment it is chosen and only joins
 * the comment when the comment is sent with `ids`. `saved` are the files a comment being changed
 * already has; taking one of those away only changes the list, which the change then saves.
 */
export function useCommentFiles(api: CommentApi, saved: Attachment[] = []) {
  const [files, setFiles] = useState<DraftFile[]>(() => saved.map(draftOf));
  const [error, setError] = useState<string | null>(null);
  const counter = useRef(0);
  const kept = useRef(new Set(saved.map((attachment) => attachment.id)));

  const add = async (list: File[]) => {
    setError(null);
    const room = MAX_COMMENT_FILES - files.length;
    const accepted: File[] = [];
    for (const file of list) {
      if (file.size > MAX_UPLOAD_BYTES) setError(messages.issues.attachments.tooLarge(file.name));
      else if (accepted.length >= room) setError(m.tooMany);
      else accepted.push(file);
    }
    const drafts = accepted.map((file): DraftFile => ({ key: `new-${++counter.current}`, name: file.name, sizeBytes: file.size, attachment: null }));
    if (drafts.length === 0) return;
    setFiles((current) => [...current, ...drafts]);
    for (const [index, file] of accepted.entries()) {
      const draft = drafts[index];
      try {
        const attachment = await api.uploadFile(file);
        setFiles((current) => current.map((item) => (item.key === draft.key ? { ...item, attachment } : item)));
      } catch (failure) {
        setFiles((current) => current.filter((item) => item.key !== draft.key));
        setError(apiErrorMessage(failure));
      }
    }
  };

  /** A file uploaded for this comment and not sent is thrown away; one the comment holds leaves when the change is saved. */
  const discardUnsent = (items: DraftFile[]) => {
    for (const item of items) {
      if (item.attachment && !kept.current.has(item.attachment.id)) void api.discardFile(item.attachment.id).catch(() => undefined);
    }
  };

  return {
    files,
    error,
    /** The uploaded files, in the order they were chosen. */
    ids: files.flatMap((item) => (item.attachment ? [item.attachment.id] : [])),
    /** Whether a file is still being uploaded. */
    busy: files.some((item) => !item.attachment),
    add,
    remove: (draft: DraftFile) => {
      setFiles((current) => current.filter((item) => item.key !== draft.key));
      discardUnsent([draft]);
    },
    /** The comment was sent: its files are its own now. */
    clear: () => {
      setFiles([]);
      setError(null);
    },
    /** Changing the comment is given up: what was uploaded for it goes, and the list is the comment's own again. */
    cancel: (original: Attachment[]) => {
      discardUnsent(files);
      setFiles(original.map(draftOf));
      setError(null);
    },
    /** Starts from the files a comment has. */
    reset: (original: Attachment[]) => {
      kept.current = new Set(original.map((attachment) => attachment.id));
      setFiles(original.map(draftOf));
      setError(null);
    },
  };
}

/**
 * Lets files be dropped or pasted onto a comment being written. Everything that is a file is taken
 * here (and kept from the editor and the browser, which would open it); text and blocks dragged
 * inside the editor pass through.
 */
export function useFileTransfer(onFiles: (files: File[]) => void) {
  const [dragging, setDragging] = useState(false);
  const hasFiles = (event: React.DragEvent) => event.dataTransfer.types.includes("Files");
  return {
    dragging,
    props: {
      onDragEnter: (event: React.DragEvent) => {
        if (hasFiles(event)) setDragging(true);
      },
      onDragOver: (event: React.DragEvent) => {
        // Without this the browser does not allow the drop.
        if (hasFiles(event)) event.preventDefault();
      },
      onDragLeave: (event: React.DragEvent) => {
        if (!event.currentTarget.contains(event.relatedTarget as Node | null)) setDragging(false);
      },
      onDropCapture: (event: React.DragEvent) => {
        setDragging(false);
        const dropped = Array.from(event.dataTransfer.files);
        if (dropped.length === 0) return;
        event.preventDefault();
        event.stopPropagation();
        onFiles(dropped);
      },
      onPasteCapture: (event: React.ClipboardEvent) => {
        const pasted = Array.from(event.clipboardData.files);
        if (pasted.length === 0) return;
        event.preventDefault();
        event.stopPropagation();
        onFiles(pasted);
      },
    },
  };
}

/** The paperclip: opens the file chooser. */
export function AttachButton({ onFiles, disabled, className }: { onFiles: (files: File[]) => void; disabled?: boolean; className?: string }) {
  const inputRef = useRef<HTMLInputElement>(null);
  return (
    <>
      <input
        ref={inputRef}
        type="file"
        multiple
        hidden
        tabIndex={-1}
        aria-hidden="true"
        onChange={(event) => {
          const chosen = Array.from(event.target.files ?? []);
          event.target.value = "";
          if (chosen.length > 0) onFiles(chosen);
        }}
      />
      <Button
        type="button"
        variant="ghost"
        size="icon-sm"
        className={cn("text-muted-foreground max-md:size-11", className)}
        aria-label={m.attach}
        title={m.attach}
        disabled={disabled}
        onClick={() => inputRef.current?.click()}
      >
        <PaperclipIcon />
      </Button>
    </>
  );
}

function FileChip({
  name,
  sizeBytes,
  url,
  uploading = false,
  onRemove,
}: {
  name: string;
  sizeBytes: number;
  /** Where to download it; a file not sent yet has none. */
  url?: string;
  uploading?: boolean;
  onRemove?: () => void;
}) {
  const label = (
    <>
      <FileIcon className="size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
      <span className="min-w-0 truncate">{name}</span>
      <span className="shrink-0 text-xs leading-4 text-muted-foreground tabular-nums">{formatSize(sizeBytes)}</span>
    </>
  );
  const labelClass = "flex min-w-0 items-center gap-2 py-1 pl-2";
  return (
    <li className="flex max-w-full items-center rounded-md border bg-card text-[0.8125rem] leading-5">
      {url ? (
        <a href={url} download={name} className={cn(labelClass, "rounded-md outline-none focus-visible:ring-3 focus-visible:ring-ring/50")}>
          {label}
        </a>
      ) : (
        <span className={labelClass}>{label}</span>
      )}
      {uploading ? (
        <span className="flex size-6 shrink-0 items-center justify-center" title={m.uploading}>
          <Spinner className="size-3.5" aria-label={m.uploading} />
        </span>
      ) : onRemove ? (
        <Button
          type="button"
          variant="ghost"
          size="icon-xs"
          className="mx-0.5 text-muted-foreground max-md:size-9"
          aria-label={m.removeFile(name)}
          title={m.removeFile(name)}
          onClick={onRemove}
        >
          <XIcon />
        </Button>
      ) : (
        <span className="w-2 shrink-0" aria-hidden="true" />
      )}
    </li>
  );
}

/** The files of a comment that was sent: each one downloads. */
export function CommentFiles({ attachments }: { attachments: Attachment[] }) {
  if (attachments.length === 0) return null;
  return (
    <ul aria-label={m.files} className="flex flex-wrap gap-1.5">
      {attachments.map((attachment) => (
        <FileChip key={attachment.id} name={attachment.fileName} sizeBytes={attachment.sizeBytes} url={attachment.url} />
      ))}
    </ul>
  );
}

/** The files of a comment being written; each can be taken off while it is not on its way up. */
export function DraftFiles({ files, onRemove }: { files: DraftFile[]; onRemove: (file: DraftFile) => void }) {
  if (files.length === 0) return null;
  return (
    <ul aria-label={m.files} className="flex flex-wrap gap-1.5">
      {files.map((file) => (
        <FileChip
          key={file.key}
          name={file.name}
          sizeBytes={file.sizeBytes}
          uploading={!file.attachment}
          onRemove={file.attachment ? () => onRemove(file) : undefined}
        />
      ))}
    </ul>
  );
}
