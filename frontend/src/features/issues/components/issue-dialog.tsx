"use client";

import { usePathname, useRouter } from "next/navigation";
import { Dialog, DialogContent, DialogTitle } from "@/components/ui/dialog";
import { Skeleton } from "@/components/ui/skeleton";
import { messages } from "@/messages";

/**
 * The frame of an issue: a dialog over a list, which stays where it was. The address is the
 * issue's own.
 *
 * Opened from inside the app, closing is going back. Opened by its address there is nothing to go
 * back to, so `closeTo` says where closing leads, and `path` is the address this dialog belongs
 * to: when another issue is opened from inside it (a sub-issue), that one comes in a dialog of
 * its own and this one steps aside until the person returns.
 */
export function IssueDialog({ children, closeTo, path }: { children: React.ReactNode; closeTo?: string; path?: string }) {
  const router = useRouter();
  // Moving on from the issue by a link inside the dialog leaves this slot showing what it last
  // showed. The address says whether the dialog still belongs on screen.
  const pathname = usePathname();
  if (path ? decodeURIComponent(pathname) !== decodeURIComponent(path) : !pathname.startsWith("/issue/")) return null;

  return (
    <Dialog
      open
      onOpenChange={(open) => {
        if (open) return;
        if (closeTo) router.push(closeTo);
        else router.back();
      }}
    >
      {/* Hung from the top rather than centred, so it does not jump as its content grows or shrinks. */}
      <DialogContent className="top-[6dvh] translate-y-0 gap-0 p-0 sm:max-w-2xl lg:max-w-5xl">
        {/* The scroll box is inside, not the dialog itself, so the editor's menus are not clipped. */}
        <div className="max-h-[88dvh] min-h-64 overflow-y-auto overscroll-contain rounded-xl">{children}</div>
      </DialogContent>
    </Dialog>
  );
}

/** What the dialog shows while the issue is being read. */
export function IssueDialogSkeleton() {
  return (
    <div className="flex flex-col gap-4 p-5 pt-10">
      <DialogTitle className="sr-only">{messages.issues.loading}</DialogTitle>
      <Skeleton className="h-7 w-2/3" />
      <Skeleton className="h-4 w-full" />
      <Skeleton className="h-4 w-5/6" />
      <Skeleton className="h-4 w-1/2" />
    </div>
  );
}
