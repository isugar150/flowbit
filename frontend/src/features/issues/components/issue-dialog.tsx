"use client";

import { usePathname, useRouter } from "next/navigation";
import { XIcon } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Dialog, DialogClose, DialogContent, DialogTitle } from "@/components/ui/dialog";
import { Skeleton } from "@/components/ui/skeleton";
import { PresenceAvatars } from "@/features/realtime/components/presence-avatars";
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
      <DialogContent
        showCloseButton={false}
        className="top-[6dvh] translate-y-0 gap-0 p-0 pb-3 sm:max-w-2xl lg:max-w-5xl xl:max-w-[min(84rem,calc(100vw-6rem))]"
      >
        {/*
          A bar of its own above the scroll box: who else is on the issue, as on a project's
          header, and the close button, which then never sits over the scroll bar. With the bar on
          top and the space below, the scroll bar also stays clear of the rounded corners, which do
          not clip it.
        */}
        <div className="flex h-11 shrink-0 items-center justify-end gap-2 pr-2 pl-4">
          <PresenceAvatars page="issue:" />
          <DialogClose render={<Button variant="ghost" size="icon-sm" />}>
            <XIcon />
            <span className="sr-only">{messages.issues.closeDialog}</span>
          </DialogClose>
        </div>
        {/* The scroll box is inside, not the dialog itself, so the editor's menus are not clipped. */}
        <div className="max-h-[calc(88dvh-3.5rem)] min-h-64 overflow-y-auto overscroll-contain">
          {/* The ticket lays itself out by its own width, which the wide panel makes narrow. */}
          <div className="@container">
            {children}
          </div>
        </div>
      </DialogContent>
    </Dialog>
  );
}

/** What the dialog shows while the issue is being read. */
export function IssueDialogSkeleton() {
  return (
    <div className="flex flex-col gap-4 p-5 pt-1">
      <DialogTitle className="sr-only">{messages.issues.loading}</DialogTitle>
      <Skeleton className="h-7 w-2/3" />
      <Skeleton className="h-4 w-full" />
      <Skeleton className="h-4 w-5/6" />
      <Skeleton className="h-4 w-1/2" />
    </div>
  );
}
