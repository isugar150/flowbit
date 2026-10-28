import { MyIssuesList } from "@/features/issues/components/my-issues-list";
import { parsePages } from "@/features/issues/paging";

export default async function MyRequestedIssuesPage({ searchParams }: PageProps<"/my-issues/created">) {
  const { deleted, pages } = await searchParams;
  return <MyIssuesList scope="created" deleted={Boolean(deleted)} pages={parsePages(pages)} />;
}
