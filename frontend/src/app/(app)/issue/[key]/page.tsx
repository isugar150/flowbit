import { IssueDetail } from "@/features/issues/components/issue-detail";

/**
 * An issue as a page of its own: what you get from a link, a bookmark or a reload. Opened from
 * inside the app it appears in a dialog instead (`@modal/(.)issue/[key]`).
 */
export default async function IssuePage({ params }: PageProps<"/issue/[key]">) {
  const { key } = await params;
  return <IssueDetail issueKey={key} />;
}
