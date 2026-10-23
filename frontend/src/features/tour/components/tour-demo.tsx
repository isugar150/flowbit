"use client";

import { ProjectIcon } from "@/components/project-icon";
import { IssueBody } from "@/features/issues/components/issue-body";
import { IssueProperties } from "@/features/issues/components/issue-properties";
import { IssuesView } from "@/features/issues/components/issues-view";
import { ProjectOverviewEditor } from "@/features/projects/components/project-overview-editor";
import { ProjectUpdates } from "@/features/projects/components/project-updates";
import { messages } from "@/messages";
import { DEMO_KEY, asSummary, type Demo } from "../demo";
import type { DemoScreen } from "../steps";

/**
 * One of the app's real screens, filled with the tour's example data: the issue list, an issue,
 * a project overview. They are the components the pages themselves use, so what a newcomer tries
 * here works the way it will later; their requests are answered from the example data instead of
 * the server (see ../demo.ts). Links do not leave the tour: an issue's title opens that issue in
 * the next step, the rest do nothing.
 */
export function TourDemo({
  screen,
  demo,
  issueNumber,
  onOpenIssue,
}: {
  screen: DemoScreen;
  demo: Demo;
  /** The issue the "issue" screen shows. */
  issueNumber: number;
  onOpenIssue: (number: number) => void;
}) {
  const projects = [asSummary(demo.project)];
  const me = demo.members[0].userId;
  const issue = demo.issues.find((item) => item.number === issueNumber) ?? demo.issues[0];

  return (
    <div
      data-tour-demo
      // Exactly where the app shows its pages (app-shell.tsx): beside the sidebar on a wide screen,
      // under the top bar on a narrow one. The menu stays in sight, as it does in the real app.
      className="fixed inset-x-0 top-12 bottom-0 isolate overflow-y-auto overscroll-contain bg-background md:top-2 md:right-2 md:bottom-2 md:left-60 md:rounded-xl md:border"
      onClickCapture={(event) => {
        const link = (event.target as Element).closest("a[href]");
        if (!link) return;
        event.preventDefault();
        const opened = new RegExp(`/issue/${DEMO_KEY}-(\\d+)$`).exec(link.getAttribute("href") ?? "");
        if (opened) onOpenIssue(Number(opened[1]));
      }}
    >
      <p className="sticky top-0 z-30 flex h-11 items-center bg-background px-5 text-[0.8125rem] leading-5 text-muted-foreground">
        {messages.tour.demo.notice}
      </p>
      {screen === "my" || screen === "list" ? (
        <IssuesView
          // The example list starts plain and leaves the person's own saved filters alone.
          rememberFilters={false}
          // The example tickets have no page to go to.
          openCreated={false}
          // The two lists are different screens: each starts on its own.
          key={screen}
          projectId={null}
          // "내 티켓" is the same list with only what the person has; the first example person plays them.
          issues={screen === "my" ? demo.issues.filter((item) => item.assignees.some((person) => person.id === me)) : demo.issues}
          defaultAssigneeId={screen === "my" ? me : null}
          members={demo.members}
          labels={demo.labels}
          projects={projects}
          canEdit
        />
      ) : screen === "issue" && issue ? (
        <div className="flex flex-col gap-8 p-4 pt-6 sm:p-5 lg:flex-row lg:items-start lg:gap-6">
          <article data-tour="issue-body" className="flex w-full min-w-0 flex-1 flex-col gap-5 px-1">
            <IssueBody
              // Another issue starts the editor over.
              key={issue.number}
              issue={{ ...issue, canEdit: true, canAddSubIssue: false, subIssues: [], reactions: [], attachments: [], documents: [] }}
              canEdit
              mentionables={[]}
            />
          </article>
          <aside data-tour="issue-properties" className="flex w-full shrink-0 flex-col gap-6 px-2 lg:w-64 lg:pt-2">
            <IssueProperties
              key={issue.number}
              issue={issue}
              members={demo.members}
              labels={demo.labels}
              projects={projects}
              canEdit
            />
          </aside>
        </div>
      ) : (
        <div className="mx-auto flex w-full max-w-3xl flex-col gap-6 px-4 pt-6 pb-10 sm:px-8">
          <div data-tour="project-description" className="flex flex-col gap-6">
            <div className="flex flex-col gap-3">
              <ProjectIcon iconUrl={null} size="lg" />
              <h2 className="text-2xl leading-8 font-semibold">{demo.project.name}</h2>
            </div>
            <ProjectOverviewEditor project={demo.project} documents={[]} canEdit />
          </div>
          <div data-tour="project-updates">
            <ProjectUpdates projectId={demo.project.id} updates={demo.updates} canEdit canManage={false} currentUserId={me} />
          </div>
        </div>
      )}
      {/* Room for the tour's card, which sits over the bottom of the screen. */}
      <div className="h-56" aria-hidden="true" />
    </div>
  );
}
