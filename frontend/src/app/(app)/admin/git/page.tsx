import type { Metadata } from "next";
import { notFound } from "next/navigation";
import { SettingsSection } from "@/components/settings-section";
import { GitAccountsPanel } from "@/features/git/components/git-accounts-panel";
import { GitConnectionsPanel } from "@/features/git/components/git-connections-panel";
import type { GitAccount, GitConnection } from "@/features/git/types";
import { serverGet } from "@/lib/current-user";
import { messages } from "@/messages";

const m = messages.git;

export const metadata: Metadata = { title: m.title };

/** The Git servers whose webhooks link issues to branches and pull requests (docs/features/git-integration.md). */
export default async function AdminGitPage() {
  const [connections, accounts] = await Promise.all([
    serverGet<GitConnection[]>("/api/v1/admin/git/connections"),
    serverGet<GitAccount[]>("/api/v1/admin/git/accounts"),
  ]);
  if (!connections) notFound();

  return (
    <>
      <SettingsSection title={m.title} description={m.description}>
        <GitConnectionsPanel connections={connections} />
      </SettingsSection>
      {/* Who is who on the Git servers: only worth asking once something is connected. */}
      {connections.length > 0 && accounts ? (
        <SettingsSection title={m.accounts.title} description={m.accounts.description}>
          <GitAccountsPanel accounts={accounts} />
        </SettingsSection>
      ) : null}
    </>
  );
}
