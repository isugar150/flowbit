"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useMutation } from "@tanstack/react-query";
import { FileTextIcon, PlusIcon } from "lucide-react";
import { TimeAgo } from "@/components/dates";
import { Button } from "@/components/ui/button";
import { apiErrorMessage } from "@/lib/api/error-message";
import { messages } from "@/messages";
import { createDocument } from "../api";
import type { IssueDocumentSummary } from "../types";

const m = messages.issues.documents;

/**
 * The documents of an issue ("자료"): pages with a title and a body written in the same editor,
 * for notes that are too long for the issue itself. "+" makes one and opens it.
 */
export function IssueDocuments({
  number,
  documents,
  canEdit,
}: {
  number: number;
  documents: IssueDocumentSummary[];
  canEdit: boolean;
}) {
  const router = useRouter();
  const create = useMutation({
    mutationFn: createDocument,
    onSuccess: (document) => router.push(`/doc/${document.id}`),
  });

  if (documents.length === 0 && !canEdit) return null;

  return (
    <section data-presence-id="documents" aria-labelledby="issue-documents-title" className="flex flex-col gap-1">
      <div className="flex h-8 items-center gap-2">
        <h3 id="issue-documents-title" className="mr-auto text-[0.8125rem] leading-5 font-medium text-muted-foreground">
          {m.heading}
        </h3>
        {canEdit ? (
          <Button
            type="button"
            variant="ghost"
            size="icon-sm"
            className="text-muted-foreground max-md:size-11"
            aria-label={m.add}
            title={m.add}
            disabled={create.isPending}
            onClick={() => create.mutate({ number, title: m.untitled })}
          >
            <PlusIcon />
          </Button>
        ) : null}
      </div>
      {documents.length > 0 ? (
        <ul className="flex flex-col">
          {documents.map((document) => (
            <li key={document.id} className="flex h-9 items-center gap-2 rounded-md px-1 hover:bg-muted max-md:h-11">
              <FileTextIcon className="size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
              <Link
                href={`/doc/${document.id}`}
                className="min-w-0 flex-1 truncate rounded-sm font-medium outline-none focus-visible:ring-3 focus-visible:ring-ring/50"
              >
                {document.title || m.untitled}
              </Link>
              <span className="shrink-0 text-xs leading-4 text-muted-foreground">
                <TimeAgo iso={document.updatedAt} />
              </span>
            </li>
          ))}
        </ul>
      ) : null}
      {create.isError ? (
        <p role="alert" className="text-[0.8125rem] leading-5 text-destructive">
          {apiErrorMessage(create.error)}
        </p>
      ) : null}
    </section>
  );
}
