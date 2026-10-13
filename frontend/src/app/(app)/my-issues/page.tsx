import { MyIssuesList } from "@/features/issues/components/my-issues-list";

export default async function MyRequestedIssuesPage({ searchParams }: PageProps<"/my-issues">) {
  const { deleted } = await searchParams;
  return <MyIssuesList scope="created" deleted={Boolean(deleted)} />;
}
