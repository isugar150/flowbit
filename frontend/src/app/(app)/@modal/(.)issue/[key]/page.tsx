import { Suspense } from "react";
import { DialogTitle } from "@/components/ui/dialog";
import { Skeleton } from "@/components/ui/skeleton";
import { IssueDetail } from "@/features/issues/components/issue-detail";
import { IssueDialog } from "@/features/issues/components/issue-dialog";
import { messages } from "@/messages";

type Params = Promise<{ key: string }>;

/**
 * Intercepts a move from inside the app to `/issue/{key}` and shows the issue in a dialog over
 * the page the person was on (a project's list, all issues, my issues). Loading the address
 * directly is not intercepted and renders `issue/[key]/page.tsx`.
 */
export default function IssueModalPage({ params }: { params: Params }) {
  return (
    <IssueDialog>
      <Suspense fallback={<IssueSkeleton />}>
        <InterceptedIssue params={params} />
      </Suspense>
    </IssueDialog>
  );
}

async function InterceptedIssue({ params }: { params: Params }) {
  const { key } = await params;
  return <IssueDetail issueKey={key} inDialog />;
}

function IssueSkeleton() {
  return (
    <div className="flex flex-col gap-4 p-5 pt-10">
      <DialogTitle className="sr-only">{messages.issues.loading}</DialogTitle>
      <Skeleton className="h-7 w-2/3" />
      <Skeleton className="h-4 w-full" />
      <Skeleton className="h-4 w-5/6" />
      <Skeleton className="h-4 w-1/2" />
    </div>
  );
}
