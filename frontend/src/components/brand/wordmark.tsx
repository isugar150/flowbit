import { cn } from "@/lib/utils";
import { messages } from "@/messages";

/** Three bits in a row, the last one lit: work moving to done. */
export function Wordmark({ className }: { className?: string }) {
  return (
    <span className={cn("inline-flex items-center gap-2 text-base font-semibold tracking-[-0.01em]", className)}>
      <svg viewBox="0 0 24 24" className="size-5 text-primary" aria-hidden="true">
        <rect x="1" y="8.5" width="6" height="7" rx="1.5" fill="currentColor" opacity="0.3" />
        <rect x="9" y="8.5" width="6" height="7" rx="1.5" fill="currentColor" opacity="0.6" />
        <rect x="17" y="8.5" width="6" height="7" rx="1.5" fill="currentColor" />
      </svg>
      {messages.app.name}
    </span>
  );
}
