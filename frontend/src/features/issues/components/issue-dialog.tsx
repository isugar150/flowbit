"use client";

import { useRouter } from "next/navigation";
import { Dialog, DialogContent } from "@/components/ui/dialog";

/**
 * The frame of an issue opened from the list: a dialog over the list, which stays where it was.
 * The address is the issue's own, so closing is going back.
 */
export function IssueDialog({ children }: { children: React.ReactNode }) {
  const router = useRouter();

  return (
    <Dialog
      open
      onOpenChange={(open) => {
        if (!open) router.back();
      }}
    >
      <DialogContent className="gap-0 p-0 sm:max-w-2xl lg:max-w-5xl">
        {/* The scroll box is inside, not the dialog itself, so the editor's menus are not clipped. */}
        <div className="max-h-[88dvh] min-h-64 overflow-y-auto overscroll-contain rounded-xl">{children}</div>
      </DialogContent>
    </Dialog>
  );
}
