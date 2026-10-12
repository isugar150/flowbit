import type { SaveStatus as Status } from "@/lib/use-autosave";
import { cn } from "@/lib/utils";
import { messages } from "@/messages";

const m = messages.editor;

/** Says whether what was typed has been saved. There is no save button, so this is the only sign. */
export function SaveStatus({ status, className }: { status: Status; className?: string }) {
  if (status === "idle") return null;
  return (
    <span
      role={status === "error" ? "alert" : "status"}
      className={cn(
        "text-[0.8125rem] leading-5",
        status === "error" ? "text-destructive" : "text-muted-foreground",
        className,
      )}
    >
      {status === "saving" ? m.saving : status === "saved" ? m.saved : m.saveFailed}
    </span>
  );
}
