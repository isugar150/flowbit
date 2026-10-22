"use client";

import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { messages } from "@/messages";

const m = messages.discard;

/**
 * Asks before text that is not saved yet is thrown away by closing what it is written in (D-182). Opened on top of
 * the thing being closed; "계속 작성" (also Esc and a click outside) goes back to it, the other button gives the text up.
 */
export function ConfirmDiscardDialog({
  open,
  onKeep,
  onDiscard,
}: {
  open: boolean;
  onKeep: () => void;
  onDiscard: () => void;
}) {
  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        if (!next) onKeep();
      }}
    >
      <DialogContent showCloseButton={false} className="sm:max-w-sm" role="alertdialog">
        <DialogHeader>
          <DialogTitle>{m.title}</DialogTitle>
          <DialogDescription>{m.description}</DialogDescription>
        </DialogHeader>
        <DialogFooter>
          <Button type="button" variant="outline" className="max-md:h-11" onClick={onKeep}>
            {m.keep}
          </Button>
          <Button type="button" variant="destructive" className="max-md:h-11" onClick={onDiscard}>
            {m.confirm}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
