import { MyIssuesList } from "@/features/issues/components/my-issues-list";

export default async function MyAssignedIssuesPage({ searchParams }: PageProps<"/my-issues">) {
  const { deleted } = await searchParams;
  return <MyIssuesList scope="assigned" deleted={Boolean(deleted)} />;
}
