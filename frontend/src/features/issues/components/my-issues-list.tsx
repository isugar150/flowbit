import Link from "next/link";
import { CircleCheckIcon } from "lucide-react";
import { DueDate } from "@/components/dates";
import { Empty, EmptyDescription, EmptyHeader, EmptyMedia, EmptyTitle } from "@/components/ui/empty";
import { serverGet } from "@/lib/current-user";
import { messages } from "@/messages";
import { PriorityIcon, STATUS_ORDER, StatusIcon, priorityLabel, statusLabel } from "../meta";
import { PersonAvatar } from "../options";
import type { Issue } from "../types";
import { LabelPill } from "./label-chip";

const m = messages.myIssues;

/**
 * "내 이슈": the signed-in person's issues from every project they can enter, grouped by status.
 * `scope` picks the ones assigned to them or the ones they created.
 */
export async function MyIssuesList({ scope }: { scope: "assigned" | "created" }) {
  const issues = (await serverGet<Issue[]>(`/api/v1/me/issues?scope=${scope}`)) ?? [];

  if (issues.length === 0) {
    return (
      <div className="flex flex-1 items-center justify-center p-6">
        <Empty>
          <EmptyHeader>
            <EmptyMedia variant="icon">
              <CircleCheckIcon />
            </EmptyMedia>
            <EmptyTitle>{scope === "assigned" ? m.emptyAssignedTitle : m.emptyCreatedTitle}</EmptyTitle>
            <EmptyDescription>{scope === "assigned" ? m.emptyAssigned : m.emptyCreated}</EmptyDescription>
          </EmptyHeader>
        </Empty>
      </div>
    );
  }

  const groups = STATUS_ORDER.map((status) => ({ status, items: issues.filter((issue) => issue.status === status) })).filter(
    (group) => group.items.length > 0,
  );

  return (
    <div className="flex flex-col">
      {groups.map((group) => (
        <section key={group.status} aria-label={statusLabel(group.status)}>
          <h2 className="flex h-9 items-center gap-2 border-y bg-muted px-5 text-[0.8125rem] leading-5 font-medium">
            <StatusIcon status={group.status} />
            {statusLabel(group.status)}
            <span className="font-normal text-muted-foreground tabular-nums">{group.items.length}</span>
          </h2>
          <ul>
            {group.items.map((issue) => (
                <li key={issue.id} className="flex h-10 items-center gap-2 border-b pr-5 pl-5 hover:bg-muted max-md:h-12">
                  <span title={priorityLabel(issue.priority)} className="flex shrink-0">
                    <PriorityIcon priority={issue.priority} />
                    <span className="sr-only">{priorityLabel(issue.priority)}</span>
                  </span>
                  <span className="w-20 shrink-0 truncate text-[0.8125rem] leading-5 text-muted-foreground tabular-nums">
                    {issue.key}
                  </span>
                  <Link
                    href={`/issue/${issue.key}`}
                    className="min-w-0 flex-1 truncate rounded-sm font-medium outline-none focus-visible:ring-3 focus-visible:ring-ring/50"
                  >
                    {issue.title}
                  </Link>
                  {issue.project ? (
                    <span className="hidden max-w-40 shrink-0 truncate text-[0.8125rem] leading-5 text-muted-foreground lg:block">
                      {issue.project.name}
                    </span>
                  ) : null}
                  {issue.labels.length > 0 ? (
                    <span className="hidden shrink-0 items-center gap-1 sm:flex">
                      {issue.labels.slice(0, 3).map((label) => (
                        <LabelPill key={label.id} label={label} />
                      ))}
                    </span>
                  ) : null}
                  {issue.dueDate ? (
                    <span className="hidden shrink-0 text-[0.8125rem] leading-5 text-muted-foreground tabular-nums sm:block">
                      <DueDate value={issue.dueDate} />
                    </span>
                  ) : null}
                  {issue.assignee ? (
                    <span title={issue.assignee.displayName ?? issue.assignee.loginId} className="flex shrink-0">
                      <PersonAvatar person={issue.assignee} />
                      <span className="sr-only">{issue.assignee.displayName ?? issue.assignee.loginId}</span>
                    </span>
                  ) : null}
                </li>
            ))}
          </ul>
        </section>
      ))}
    </div>
  );
}
