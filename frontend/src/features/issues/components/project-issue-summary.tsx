import Link from "next/link";
import { messages } from "@/messages";
import { STATUS_ORDER, StatusIcon, statusLabel } from "../meta";
import type { Issue } from "../types";

const m = messages.issues.summary;

/**
 * The tickets linked to a project, on its overview: how many are done and how many are in each
 * status. Each status opens the project's list filtered to it. Canceled tickets are not counted
 * as work to do.
 */
export function ProjectIssueSummary({ projectId, issues, canEdit }: { projectId: string; issues: Issue[]; canEdit: boolean }) {
  const list = `/project/${encodeURIComponent(projectId)}/issues`;
  const counts = STATUS_ORDER.map((status) => ({ status, count: issues.filter((issue) => issue.status === status).length }));
  const done = counts.find((item) => item.status === "DONE")?.count ?? 0;
  const total = issues.length - (counts.find((item) => item.status === "CANCELED")?.count ?? 0);
  const percent = total === 0 ? 0 : Math.round((done / total) * 100);

  return (
    <section className="flex flex-col gap-2" aria-labelledby="project-issues-title">
      <div className="flex items-center justify-between gap-3">
        <h3 id="project-issues-title" className="text-[0.8125rem] leading-5 font-medium text-muted-foreground">
          {m.title}
        </h3>
        <Link
          href={list}
          className="rounded-md px-1.5 text-[0.8125rem] leading-6 text-muted-foreground outline-none hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50"
        >
          {m.viewAll}
        </Link>
      </div>
      {issues.length === 0 ? (
        <p className="rounded-lg border px-3 py-3 text-[0.8125rem] leading-5 text-muted-foreground">
          {canEdit ? m.empty : m.emptyReadOnly}
        </p>
      ) : (
        <div className="flex flex-col gap-3 rounded-lg border px-3 py-3">
          <div className="flex items-center gap-3">
            <span
              role="progressbar"
              aria-valuemin={0}
              aria-valuemax={100}
              aria-valuenow={percent}
              aria-label={m.progress(done, total)}
              className="h-1.5 min-w-0 flex-1 overflow-hidden rounded-full bg-muted-foreground/20"
            >
              <span className="block h-full rounded-full bg-primary" style={{ width: `${percent}%` }} />
            </span>
            <span className="shrink-0 text-[0.8125rem] leading-5 font-medium tabular-nums">{m.progress(done, total)}</span>
          </div>
          <ul className="flex flex-wrap gap-x-1 gap-y-1">
            {counts
              .filter((item) => item.count > 0)
              .map(({ status, count }) => (
                <li key={status}>
                  <Link
                    href={`${list}?status=${status}`}
                    aria-label={m.count(statusLabel(status), count)}
                    className="flex h-7 items-center gap-1.5 rounded-md px-2 text-[0.8125rem] leading-5 outline-none hover:bg-accent focus-visible:ring-3 focus-visible:ring-ring/50 max-md:h-9"
                  >
                    <StatusIcon status={status} />
                    <span className="text-muted-foreground">{statusLabel(status)}</span>
                    <span className="font-medium tabular-nums">{count}</span>
                  </Link>
                </li>
              ))}
          </ul>
        </div>
      )}
    </section>
  );
}
