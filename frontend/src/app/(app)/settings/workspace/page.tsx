import type { Metadata } from "next";
import { redirect } from "next/navigation";
import { WorkspaceSettings } from "@/features/settings/components/workspace-settings";
import { getCurrentUser, getWorkspace } from "@/lib/current-user";
import { messages } from "@/messages";
import { SettingsSection } from "../_components/settings-section";

const m = messages.settings.workspace;

export const metadata: Metadata = { title: m.title };

export default async function WorkspaceSettingsPage() {
  const [user, workspace] = await Promise.all([getCurrentUser(), getWorkspace()]);
  if (!user || !workspace) redirect("/login");
  // Hiding the page is a convenience; the backend refuses these changes from non-administrators.
  if (user.role !== "ADMIN") redirect("/settings/profile");

  return (
    <SettingsSection title={m.title} description={m.description}>
      <WorkspaceSettings workspace={workspace} />
    </SettingsSection>
  );
}
