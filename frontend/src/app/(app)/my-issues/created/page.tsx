import { MyIssuesList } from "@/features/issues/components/my-issues-list";

export default async function MyCreatedIssuesPage({ searchParams }: PageProps<"/my-issues/created">) {
  const { deleted } = await searchParams;
  return <MyIssuesList scope="created" deleted={Boolean(deleted)} />;
}
