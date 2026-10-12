import type { Metadata } from "next";
import { SettingsSection } from "@/components/settings-section";
import { WorkspaceSettings } from "@/features/settings/components/workspace-settings";
import { getWorkspace } from "@/lib/current-user";
import { messages } from "@/messages";

const m = messages.settings.workspace;

export const metadata: Metadata = { title: m.title };

export default async function AdminWorkspacePage() {
  const workspace = await getWorkspace();

  return (
    <SettingsSection title={m.title} description={m.description}>
      <WorkspaceSettings workspace={workspace} />
    </SettingsSection>
  );
}
