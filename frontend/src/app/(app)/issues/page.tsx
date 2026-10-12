import type { Metadata } from "next";
import { AllIssues } from "@/features/issues/components/all-issues";
import { messages } from "@/messages";

export const metadata: Metadata = { title: messages.issues.all };

/** `?deleted=1` shows the deleted issues instead of the ones in use. */
export default async function AllIssuesPage({ searchParams }: PageProps<"/issues">) {
  const { deleted } = await searchParams;
  return <AllIssues deleted={Boolean(deleted)} />;
}
