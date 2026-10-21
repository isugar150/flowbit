"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { useMutation } from "@tanstack/react-query";
import { EllipsisIcon, Trash2Icon } from "lucide-react";
import { DropdownMenu, DropdownMenuContent, DropdownMenuGroup, DropdownMenuItem, DropdownMenuTrigger } from "@/components/ui/dropdown-menu";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { apiErrorMessage } from "@/lib/api/error-message";
import { messages } from "@/messages";
import { deleteDocument } from "../api";
import type { IssueDocument } from "../types";

const m = messages.issues.documents;

/**
 * The "…" at the top right of a document, for what is done to the document as a whole. For now
 * that is deleting it: it asks for a second press (the menu stays open and the item says so), takes
 * the documents inside along, and goes back to where the document was. Only for who may edit it.
 */
export function DocumentMenu({ document }: { document: IssueDocument }) {
  const router = useRouter();
  const [confirming, setConfirming] = useState(false);
  const remove = useMutation({
    mutationFn: deleteDocument,
    onSuccess: () => {
      const parent = document.ancestors.at(-1);
      router.replace(
        parent ? `/doc/${parent.id}` : document.issue ? `/issue/${document.issue.key}` : `/project/${document.project?.id ?? ""}`,
      );
      router.refresh();
    },
  });

  if (!document.canEdit) return null;

  return (
    <>
      <DropdownMenu onOpenChange={() => setConfirming(false)}>
        <Tooltip>
          <TooltipTrigger
            render={
              <DropdownMenuTrigger
                render={
                  <button
                    type="button"
                    aria-label={m.more}
                    className="flex size-7 shrink-0 items-center justify-center rounded-md text-muted-foreground outline-none hover:bg-muted hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50 aria-expanded:bg-muted max-md:size-9"
                  />
                }
              />
            }
          >
            <EllipsisIcon className="size-4" aria-hidden="true" />
          </TooltipTrigger>
          <TooltipContent>{m.more}</TooltipContent>
        </Tooltip>
        <DropdownMenuContent align="end" className="w-56">
          <DropdownMenuGroup>
            <DropdownMenuItem
              variant="destructive"
              disabled={remove.isPending}
              // The first press only asks again; the second one deletes.
              closeOnClick={confirming}
              onClick={() => (confirming ? remove.mutate(document.id) : setConfirming(true))}
            >
              <Trash2Icon />
              {confirming ? m.deleteConfirm : m.delete}
            </DropdownMenuItem>
          </DropdownMenuGroup>
        </DropdownMenuContent>
      </DropdownMenu>
      {remove.isError ? (
        // Rare, and the bar has no room for more: the reason is short and stays beside the button.
        <span role="alert" className="max-w-56 truncate text-[0.8125rem] leading-5 text-destructive">
          {apiErrorMessage(remove.error)}
        </span>
      ) : null}
    </>
  );
}
