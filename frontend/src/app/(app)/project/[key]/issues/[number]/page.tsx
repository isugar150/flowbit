import Link from "next/link";
import { notFound } from "next/navigation";
import { ChevronLeftIcon } from "lucide-react";
import { Person } from "@/components/person";
import { PropertyCard, PropertyRow } from "@/components/property-card";
import { IssueBody } from "@/features/issues/components/issue-body";
import { IssueProperties } from "@/features/issues/components/issue-properties";
import type { Issue } from "@/features/issues/types";
import type { Project, ProjectMember } from "@/features/projects/types";
import { requireUser, serverGet } from "@/lib/current-user";
import { messages } from "@/messages";

const m = messages.issues;

/** One issue: what it says in the middle, its properties in a card on the right. */
export default async function IssuePage({ params }: PageProps<"/project/[key]/issues/[number]">) {
  const { key, number } = await params;
  if (!/^\d{1,9}$/.test(number)) notFound();
  const path = `/api/v1/projects/${encodeURIComponent(key)}`;
  const [user, project, issue, members] = await Promise.all([
    requireUser(),
    serverGet<Project>(path),
    serverGet<Issue>(`${path}/issues/${number}`),
    serverGet<ProjectMember[]>(`${path}/members`),
  ]);
  if (!project || !issue || !members) notFound();
  const created = new Intl.DateTimeFormat("sv-SE", { timeZone: user.timezone }).format(new Date(issue.createdAt));

  return (
    <div className="flex flex-1 flex-col gap-8 px-4 pt-2 pb-10 lg:flex-row lg:items-start lg:gap-6">
      <article className="mx-auto flex w-full max-w-3xl min-w-0 flex-1 flex-col gap-5 px-2 pt-4 sm:px-6 lg:pt-8">
        <Link
          href={`/project/${project.key}/issues`}
          className="flex w-fit items-center gap-1 rounded-md text-[0.8125rem] leading-5 text-muted-foreground outline-none hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50"
        >
          <ChevronLeftIcon className="size-4" aria-hidden="true" />
          {m.backToList}
        </Link>
        <IssueBody projectKey={project.key} issue={issue} canEdit={project.myRole !== "VIEWER"} />
      </article>
      <aside className="mx-auto flex w-full max-w-3xl shrink-0 flex-col gap-2 lg:mx-0 lg:w-80">
        <PropertyCard title={messages.projects.properties}>
          <IssueProperties
            projectKey={project.key}
            issue={issue}
            members={members}
            canEdit={project.myRole !== "VIEWER"}
          />
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
