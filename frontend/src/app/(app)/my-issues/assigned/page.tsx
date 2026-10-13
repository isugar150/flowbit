import { MyIssuesList } from "@/features/issues/components/my-issues-list";

export default async function MyAssignedIssuesPage({ searchParams }: PageProps<"/my-issues/assigned">) {
  const { deleted } = await searchParams;
  return <MyIssuesList scope="assigned" deleted={Boolean(deleted)} />;
}
