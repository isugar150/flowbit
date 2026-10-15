import type { Metadata } from "next";
import { notFound } from "next/navigation";
import { SettingsSection } from "@/components/settings-section";
import { GitConnectionsPanel } from "@/features/git/components/git-connections-panel";
import type { GitConnection } from "@/features/git/types";
import { getProjects, serverGet } from "@/lib/current-user";
import { messages } from "@/messages";

const m = messages.git;

export const metadata: Metadata = { title: m.title };

/** The Git servers whose webhooks link issues to branches and pull requests (docs/features/git-integration.md). */
export default async function AdminGitPage() {
  const [connections, projects] = await Promise.all([serverGet<GitConnection[]>("/api/v1/admin/git/connections"), getProjects()]);
  if (!connections) notFound();

  return (
    <SettingsSection title={m.title} description={m.description}>
      <GitConnectionsPanel connections={connections} projects={projects} />
    </SettingsSection>
  );
}
