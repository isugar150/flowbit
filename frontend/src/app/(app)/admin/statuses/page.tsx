import type { Metadata } from "next";
import { notFound } from "next/navigation";
import { SettingsSection } from "@/components/settings-section";
import { StatusSettings } from "@/features/issues/components/status-settings";
import type { IssueStatusItem } from "@/features/issues/status-defaults";
import { serverGet } from "@/lib/current-user";
import { messages } from "@/messages";

const m = messages.issueStatuses;

export const metadata: Metadata = { title: m.title };

/** The statuses an issue can be in, by category (docs/features/statuses-and-estimates.md). */
export default async function AdminStatusesPage() {
  const statuses = await serverGet<IssueStatusItem[]>("/api/v1/issue-statuses");
  if (!statuses) notFound();

  return (
    <SettingsSection title={m.title} description={m.description}>
      <StatusSettings statuses={statuses} />
    </SettingsSection>
  );
}
