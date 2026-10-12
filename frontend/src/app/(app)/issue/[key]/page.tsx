import type { Metadata } from "next";
import { Suspense } from "react";
import { AllIssues } from "@/features/issues/components/all-issues";
import { IssueDetail } from "@/features/issues/components/issue-detail";
import { IssueDialog, IssueDialogSkeleton } from "@/features/issues/components/issue-dialog";
import { issueNumberOf } from "@/features/issues/meta";
import type { Issue } from "@/features/issues/types";
import { serverGet } from "@/lib/current-user";

type Params = PageProps<"/issue/[key]">["params"];

/** "FB-12 제목" in the browser tab when the issue's address is opened directly. */
export async function generateMetadata({ params }: { params: Params }): Promise<Metadata> {
  const { key } = await params;
  const number = issueNumberOf(key);
  const issue = number === null ? null : await serverGet<Issue>(`/api/v1/issues/${number}`);
  return issue ? { title: `${issue.key} ${issue.title}` } : {};
}

/**
 * An issue's address opened directly (a link, a bookmark, a reload). The issue is a dialog here
 * too, the same as when it is opened from inside the app (`@modal/(.)issue/[key]`); there is no
 * page behind it to return to, so it stands over the list of all issues and closing goes there.
 */
export default function IssuePage({ params }: { params: Params }) {
  return (
    <>
      <AllIssues />
      <Suspense fallback={null}>
        <DirectIssue params={params} />
      </Suspense>
    </>
  );
}

async function DirectIssue({ params }: { params: Params }) {
  const { key } = await params;
  return (
    <IssueDialog closeTo="/issues" path={`/issue/${key}`}>
      <Suspense fallback={<IssueDialogSkeleton />}>
        <IssueDetail issueKey={key} />
      </Suspense>
    </IssueDialog>
  );
}
