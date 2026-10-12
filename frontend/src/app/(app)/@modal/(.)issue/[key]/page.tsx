import { Suspense } from "react";
import { IssueDetail } from "@/features/issues/components/issue-detail";
import { IssueDialog, IssueDialogSkeleton } from "@/features/issues/components/issue-dialog";

type Params = Promise<{ key: string }>;

/**
 * Intercepts a move from inside the app to `/issue/{key}` and shows the issue in a dialog over
 * the page the person was on (a project's list, all issues, my issues). Loading the address
 * directly is not intercepted and renders `issue/[key]/page.tsx`.
 */
export default function IssueModalPage({ params }: { params: Params }) {
  return (
    <IssueDialog>
      <Suspense fallback={<IssueDialogSkeleton />}>
        <InterceptedIssue params={params} />
      </Suspense>
    </IssueDialog>
  );
}

async function InterceptedIssue({ params }: { params: Params }) {
  const { key } = await params;
  return <IssueDetail issueKey={key} />;
}
