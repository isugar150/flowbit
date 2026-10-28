import { MyIssuesList } from "@/features/issues/components/my-issues-list";
import { parsePages } from "@/features/issues/paging";

export default async function MyAssignedIssuesPage({ searchParams }: PageProps<"/my-issues">) {
  const { deleted, pages } = await searchParams;
  return <MyIssuesList scope="assigned" deleted={Boolean(deleted)} pages={parsePages(pages)} />;
}
