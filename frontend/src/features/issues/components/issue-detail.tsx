import Link from "next/link";
import { notFound } from "next/navigation";
import { CornerLeftUpIcon } from "lucide-react";
import { DocumentTitle } from "@/components/document-title";
import { Person } from "@/components/person";
import { DialogTitle } from "@/components/ui/dialog";
import type { UserSummary } from "@/features/members/types";
import type { Milestone } from "@/features/milestones/types";
import { mentionablesOf, usersAsMembers, type ProjectMember } from "@/features/projects/types";
import { branchName } from "@/features/git/branch-name";
import { IssueGitLinks } from "@/features/git/components/issue-git-links";
import { RealtimeScope } from "@/features/realtime/realtime-provider";
import { getProjects, requireUser, serverGet } from "@/lib/current-user";
import { messages } from "@/messages";
import { issueNumberOf } from "../meta";
import type { IssueActivityFeed, IssueDetail as IssueDetailData, Label } from "../types";
import { IssueActivity } from "./issue-activity";
import { IssueAttachments } from "./issue-attachments";
import { IssueBody } from "./issue-body";
import { IssueLeaveNotifier } from "./issue-leave-notifier";
import { DeleteIssueButton, DeletedIssueBanner } from "./issue-deletion";
import { IssueProperties } from "./issue-properties";
import { IssueReactions } from "./issue-reactions";
import { IssueSubIssues } from "./issue-sub-issues";

const m = messages.issues;

/**
 * One issue: what it says on the left, its properties on the right. Always shown inside
 * IssueDialog; an issue has no page of its own. `issueKey` is what the address carries, such as
 * FB-12; only the number in it matters.
 */
export async function IssueDetail({ issueKey }: { issueKey: string }) {
  const number = issueNumberOf(issueKey);
  if (number === null) notFound();
  const [user, issue, activity, labels, projects, git] = await Promise.all([
    requireUser(),
    serverGet<IssueDetailData>(`/api/v1/issues/${number}`),
    serverGet<IssueActivityFeed>(`/api/v1/issues/${number}/activity`),
    serverGet<Label[]>("/api/v1/labels"),
    getProjects(),
    serverGet<{ enabled: boolean }>("/api/v1/git/status"),
  ]);
  if (!issue || !labels) notFound();
  // Who can be assigned and which milestones exist depend on the project the issue is linked to.
  const projectPath = issue.project ? `/api/v1/projects/${encodeURIComponent(issue.project.id)}` : null;
  const [members, milestones] = await Promise.all([
    projectPath
      ? serverGet<ProjectMember[]>(`${projectPath}/members`)
      : serverGet<UserSummary[]>("/api/v1/users").then(usersAsMembers),
    projectPath ? serverGet<Milestone[]>(`${projectPath}/milestones`) : Promise.resolve([]),
  ]);
  const created = new Intl.DateTimeFormat("sv-SE", { timeZone: user.timezone }).format(new Date(issue.createdAt));
  const canEdit = issue.canEdit;
  // Whoever could be given the issue can be tagged in it.
  const mentionables = mentionablesOf(members ?? []);

  return (
    <div className="flex flex-1 flex-col gap-8 p-4 pt-1 sm:p-5 sm:pt-1 lg:flex-row lg:items-start lg:gap-6">
      {/* Presence on an issue is about this issue, not the list behind it. */}
      <RealtimeScope topics={[`issue:${issue.number}`]} presence={`issue:${issue.number}`} priority={1} />
      {/* Only someone who can change the issue has changes waiting to be announced. */}
      {canEdit ? <IssueLeaveNotifier number={issue.number} /> : null}
      <article className="flex w-full min-w-0 flex-1 flex-col gap-5 px-1">
        <DialogTitle className="sr-only">{`${issue.key} ${issue.title}`}</DialogTitle>
        <DocumentTitle title={`${issue.key} ${issue.title}`} />
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
        <DeletedIssueBanner issue={issue} />
        <IssueBody
          issue={issue}
          canEdit={canEdit}
          mentionables={mentionables}
          branch={git?.enabled ? branchName(user.loginId, issue.key, issue.title) : null}
        />
        <IssueAttachments number={issue.number} attachments={issue.attachments} canEdit={canEdit} />
        <IssueReactions number={issue.number} reactions={issue.reactions} readOnly={Boolean(issue.deletedAt)} />
        <IssueSubIssues issue={issue} canEdit={canEdit} members={members ?? []} />
        {activity ? <IssueActivity number={issue.number} feed={activity} canComment={canEdit} mentionables={mentionables} /> : null}
      </article>
      <aside className="flex w-full shrink-0 flex-col gap-6 px-2 lg:w-64 lg:pt-2">
        <IssueProperties
          issue={issue}
          members={members ?? []}
          labels={labels}
          projects={projects}
          milestones={milestones ?? []}
          canEdit={canEdit}
        />
        <IssueGitLinks links={issue.git ?? []} />
        <section className="flex flex-col gap-1">
          <h3 className="text-[0.8125rem] leading-5 font-medium text-muted-foreground">{m.createdBy}</h3>
          <div className="flex min-h-8 items-center justify-between gap-2">
            <Person person={issue.createdBy} />
            <span className="shrink-0 text-[0.8125rem] leading-5 text-muted-foreground tabular-nums">{created}</span>
          </div>
        </section>
        <DeleteIssueButton issue={issue} />
      </aside>
    </div>
  );
}
