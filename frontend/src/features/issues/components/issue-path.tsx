import { Fragment } from "react";
import { ChevronRightIcon } from "lucide-react";
import { cn } from "@/lib/utils";
import { messages } from "@/messages";

const m = messages.issues.subIssues;

/**
 * The issues above a sub-issue, the top-level one first and its own parent last, as one line:
 * "FB-1 정산 개편 › FB-2 정산 API". It tells where in the tree an issue hangs. Nothing for a
 * top-level issue. The line is cut at its end, so the top-level issue stays in view; the whole
 * path is in its title.
 */
export function IssuePath({ ancestors, className }: { ancestors: { key: string; title: string }[]; className?: string }) {
  if (ancestors.length === 0) return null;
  return (
    <span
      title={ancestors.map((ancestor) => `${ancestor.key} ${ancestor.title}`).join(" › ")}
      className={cn("flex min-w-0 items-center gap-1 text-[0.75rem] leading-5 text-muted-foreground", className)}
    >
      <span className="sr-only">{m.path}</span>
      {ancestors.map((ancestor, index) => (
        <Fragment key={ancestor.key}>
          {index > 0 ? <ChevronRightIcon className="size-3 shrink-0" aria-hidden="true" /> : null}
          <span className="flex min-w-0 shrink items-center gap-1">
            <span className="shrink-0 tabular-nums">{ancestor.key}</span>
            <span className="truncate">{ancestor.title}</span>
          </span>
        </Fragment>
      ))}
    </span>
  );
}
