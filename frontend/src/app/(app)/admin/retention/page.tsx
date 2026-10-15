import type { Metadata } from "next";
import { notFound } from "next/navigation";
import { SettingsSection } from "@/components/settings-section";
import { RetentionSettingsForm } from "@/features/retention/components/retention-settings-form";
import type { RetentionSettings } from "@/features/retention/types";
import { serverGet } from "@/lib/current-user";
import { messages } from "@/messages";

const m = messages.retention;

export const metadata: Metadata = { title: m.title };

/** How long deleted issues, documents and comments are kept (docs/features/admin-retention.md). */
export default async function AdminRetentionPage() {
  const settings = await serverGet<RetentionSettings>("/api/v1/admin/retention");
  if (!settings) notFound();

  return (
    <SettingsSection title={m.title} description={m.description}>
      <RetentionSettingsForm settings={settings} />
    </SettingsSection>
  );
}
