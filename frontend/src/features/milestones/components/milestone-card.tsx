import { SquareCheckIcon, SquareIcon } from "lucide-react";
import { cn } from "@/lib/utils";
import { messages } from "@/messages";
import { hasOutline, type MilestoneOutline } from "../outline";

const m = messages.milestones;
const CRITERIA_SHOWN = 5;

/** A milestone's own address: its project's overview with the milestone's dialog over it (D-160). */
export function milestoneHref(projectId: string, milestoneId: string): string {
  return `/project/${encodeURIComponent(projectId)}/milestone/${encodeURIComponent(milestoneId)}`;
}

/** The purpose line and the checklist of completion criteria, compact. Nothing when the document says neither. */
export function OutlineView({ outline, className }: { outline: MilestoneOutline; className?: string }) {
  if (!hasOutline(outline)) return null;
  const shown = outline.criteria.slice(0, CRITERIA_SHOWN);
  const done = outline.criteria.filter((item) => item.checked).length;
  return (
    <div className={cn("flex min-w-0 flex-col gap-1.5 text-[0.8125rem] leading-5 [overflow-wrap:anywhere]", className)}>
      {outline.purpose ? <p className="line-clamp-2 text-foreground">{outline.purpose}</p> : null}
      {shown.length > 0 ? (
        <div className="flex flex-col gap-0.5">
          <span className="text-xs text-muted-foreground">
            {m.criteriaTitle} <span className="tabular-nums">{done}/{outline.criteria.length}</span>
          </span>
          <ul className="flex flex-col gap-0.5">
            {shown.map((item, index) => (
              <li key={index} className={cn("flex items-start gap-1.5", item.checked && "text-muted-foreground line-through")}>
                {item.checked ? (
                  <SquareCheckIcon className="mt-0.5 size-3.5 shrink-0 text-primary" aria-hidden="true" />
                ) : (
                  <SquareIcon className="mt-0.5 size-3.5 shrink-0 text-muted-foreground" aria-hidden="true" />
                )}
                <span className="sr-only">{item.checked ? m.criterionDone : m.criterionOpen}</span>
                <span className="line-clamp-2 min-w-0">{item.text}</span>
              </li>
            ))}
          </ul>
          {outline.criteria.length > shown.length ? (
            <span className="text-xs text-muted-foreground">{m.moreCriteria(outline.criteria.length - shown.length)}</span>
          ) : null}
        </div>
      ) : null}
    </div>
  );
}
