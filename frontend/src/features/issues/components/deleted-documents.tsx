"use client";

import { useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ChevronDownIcon, FileTextIcon } from "lucide-react";
import { TimeAgo } from "@/components/dates";
import { Button } from "@/components/ui/button";
import { apiFetch } from "@/lib/api/client";
import { apiErrorMessage } from "@/lib/api/error-message";
import { cn } from "@/lib/utils";
import { messages } from "@/messages";
import { restoreDocument } from "../api";
import type { IssueDocumentSummary } from "../types";

const m = messages.issues.documents;

/**
 * The deleted documents of an issue or a project, folded away under what is written there.
 * Deleting a document only archives it: each one here can be opened (read-only) or restored.
 * `path` is where the list is read from. It is read with the page, and the section only shows when
 * there is something in it (the user's call, 2026-10-15: an empty "삭제된 문서" was noise).
 */
export function DeletedDocuments({ path, canEdit }: { path: string; canEdit: boolean }) {
  const router = useRouter();
  const queryClient = useQueryClient();
  const [open, setOpen] = useState(false);
  const key = ["deleted-documents", path];
  const documents = useQuery({
    queryKey: key,
    queryFn: () => apiFetch<IssueDocumentSummary[]>(path),
    staleTime: 0,
  });
  const restore = useMutation({
    mutationFn: restoreDocument,
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: key });
      // The restored document is back among the documents the page itself read.
      router.refresh();
    },
  });

  // Nothing to show until it is known that there is something; a failed read says so once opened.
  if (documents.isPending || documents.data?.length === 0) return null;

  return (
    <section className="flex flex-col gap-1">
      <button
        type="button"
        aria-expanded={open}
        onClick={() => setOpen((current) => !current)}
        className="flex h-7 items-center gap-1 self-start rounded-md px-1 text-xs font-medium text-muted-foreground outline-none hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50 max-md:h-11"
      >
        <ChevronDownIcon className={cn("size-3", open ? null : "-rotate-90")} aria-hidden="true" />
        {m.deletedTitle}
      </button>
      {!open ? null : documents.isError ? (
        <p role="alert" className="px-1 text-[0.8125rem] leading-5 text-destructive">
          {apiErrorMessage(documents.error)}
        </p>
      ) : (
        <ul className="flex flex-col">
          {documents.data.map((document) => (
            <li key={document.id} className="flex min-h-9 items-center gap-2 px-1 text-[0.8125rem] leading-5 max-md:min-h-11">
              <FileTextIcon className="size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
              <Link
                href={`/doc/${document.id}`}
                className="min-w-0 flex-1 truncate rounded-sm text-muted-foreground outline-none hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50"
              >
                {document.title || m.untitled}
              </Link>
              {document.deletedAt ? (
                <span className="shrink-0 text-xs leading-4 text-muted-foreground max-sm:hidden">
                  {m.deletedAt} <TimeAgo iso={document.deletedAt} />
                </span>
              ) : null}
              {canEdit ? (
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  className="max-md:h-11"
                  disabled={restore.isPending}
                  aria-label={m.restoreOf(document.title || m.untitled)}
                  onClick={() => restore.mutate(document.id)}
                >
                  {m.restore}
                </Button>
              ) : null}
            </li>
          ))}
        </ul>
      )}
      {restore.isError ? (
        <p role="alert" className="px-1 text-[0.8125rem] leading-5 text-destructive">
          {apiErrorMessage(restore.error)}
        </p>
      ) : null}
    </section>
  );
}
