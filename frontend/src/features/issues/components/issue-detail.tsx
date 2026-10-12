import Link from "next/link";
import { notFound } from "next/navigation";
import { ChevronLeftIcon, CornerLeftUpIcon } from "lucide-react";
import { Person } from "@/components/person";
import { PropertyCard, PropertyRow } from "@/components/property-card";
import { DialogTitle } from "@/components/ui/dialog";
import type { UserSummary } from "@/features/members/types";
import type { Milestone } from "@/features/milestones/types";
import type { ProjectMember } from "@/features/projects/types";
import { RealtimeScope } from "@/features/realtime/realtime-provider";
import { getProjects, requireUser, serverGet } from "@/lib/current-user";
import { cn } from "@/lib/utils";
import { messages } from "@/messages";
import { issueNumberOf } from "../meta";
import type { IssueActivityFeed, IssueDetail as IssueDetailData, Label } from "../types";
import { IssueActivity } from "./issue-activity";
import { IssueAttachments } from "./issue-attachments";
import { IssueBody } from "./issue-body";
import { IssueDocuments } from "./issue-documents";
import { IssueProjectLink, IssueProperties } from "./issue-properties";
import { IssueReactions } from "./issue-reactions";
import { IssueSubIssues } from "./issue-sub-issues";

const m = messages.issues;

/**
 * One issue: what it says on the left, its properties in cards on the right. Shown in a dialog
 * over the page it was opened from, and as a page of its own when the address is opened directly.
 * `issueKey` is what the address carries, such as FB-12; only the number in it matters.
 */
export async function IssueDetail({ issueKey, inDialog = false }: { issueKey: string; inDialog?: boolean }) {
  const number = issueNumberOf(issueKey);
  if (number === null) notFound();
  const [user, issue, activity, labels, projects] = await Promise.all([
    requireUser(),
    serverGet<IssueDetailData>(`/api/v1/issues/${number}`),
    serverGet<IssueActivityFeed>(`/api/v1/issues/${number}/activity`),
    serverGet<Label[]>("/api/v1/labels"),
    getProjects(),
  ]);
  if (!issue || !labels) notFound();
  // Who can be assigned and which milestones exist depend on the project the issue is linked to.
  const projectPath = issue.project ? `/api/v1/projects/${encodeURIComponent(issue.project.key)}` : null;
  const [members, milestones] = await Promise.all([
    projectPath
      ? serverGet<ProjectMember[]>(`${projectPath}/members`)
      : serverGet<UserSummary[]>("/api/v1/users").then((users) =>
          (users ?? []).map((person): ProjectMember => ({ ...person, userId: person.id, role: "EDITOR" })),
        ),
    projectPath ? serverGet<Milestone[]>(`${projectPath}/milestones`) : Promise.resolve([]),
  ]);
  const created = new Intl.DateTimeFormat("sv-SE", { timeZone: user.timezone }).format(new Date(issue.createdAt));
  const canEdit = issue.canEdit;

  return (
    <div
      className={cn(
        "flex flex-1 flex-col gap-8 lg:flex-row lg:items-start lg:gap-6",
        inDialog ? "p-4 pt-10 sm:p-5 sm:pt-10" : "px-4 pt-2 pb-10",
      )}
    >
      {/* Presence on an issue is about this issue, not the page behind it. */}
      <RealtimeScope topics={[`issue:${issue.number}`]} presence={`issue:${issue.number}`} priority={1} />
      <article
        className={cn(
          "flex w-full min-w-0 flex-1 flex-col gap-5",
          inDialog ? "px-1" : "mx-auto max-w-3xl px-2 pt-4 sm:px-6 lg:pt-8",
        )}
      >
        {inDialog ? (
          <DialogTitle className="sr-only">{`${issue.key} ${issue.title}`}</DialogTitle>
        ) : (
          <Link
            href={issue.project ? `/project/${issue.project.key}/issues` : "/issues"}
            className="flex w-fit items-center gap-1 rounded-md text-[0.8125rem] leading-5 text-muted-foreground outline-none hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50"
          >
            <ChevronLeftIcon className="size-4" aria-hidden="true" />
            {m.backToList}
          </Link>
        )}
        {issue.parent ? (
          <Link
            href={`/issue/${issue.parent.key}`}
            className="-mb-3 flex w-fit max-w-full items-center gap-1.5 rounded-md text-[0.8125rem] leading-5 text-muted-foreground outline-none hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50"
          >
            <CornerLeftUpIcon className="size-3.5 shrink-0" aria-hidden="true" />
            <span className="shrink-0">{m.subIssueOf}</span>
            <span className="shrink-0 tabular-nums">{issue.parent.key}</span>
            <span className="truncate">{issue.parent.title}</span>
          </Link>
        ) : null}
        <IssueBody issue={issue} canEdit={canEdit} />
        <IssueAttachments number={issue.number} attachments={issue.attachments} canEdit={canEdit} />
        <IssueReactions number={issue.number} reactions={issue.reactions} />
        <IssueSubIssues issue={issue} canEdit={canEdit} />
        <IssueDocuments number={issue.number} documents={issue.documents} canEdit={canEdit} />
        {activity ? <IssueActivity number={issue.number} feed={activity} /> : null}
      </article>
      <aside className={cn("flex w-full shrink-0 flex-col gap-2 lg:w-80", inDialog ? "lg:w-72" : "mx-auto max-w-3xl lg:mx-0")}>
        <PropertyCard title={messages.projects.properties}>
          <IssueProperties issue={issue} members={members ?? []} labels={labels} canEdit={canEdit} />
        </PropertyCard>
        <PropertyCard title={m.project}>
          <IssueProjectLink issue={issue} projects={projects} milestones={milestones ?? []} canEdit={canEdit} />
        </PropertyCard>
        <PropertyCard title={m.createdBy}>
          <dl className="flex flex-col">
            <div className="px-1 py-1">
              <Person person={issue.createdBy} />
            </div>
            <PropertyRow label={m.createdAt}>
              <span className="tabular-nums">{created}</span>
            </PropertyRow>
          </dl>
        </PropertyCard>
      </aside>
    </div>
  );
}
