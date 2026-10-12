import type { Metadata } from "next";
import { SettingsSection } from "@/components/settings-section";
import { MembersPanel } from "@/features/members/components/members-panel";
import type { Member } from "@/features/members/types";
import { fetchAuthMethods } from "@/lib/backend";
import { requireUser, serverGet } from "@/lib/current-user";
import { messages } from "@/messages";

const m = messages.members;

export const metadata: Metadata = { title: m.title };

export default async function AdminMembersPage() {
  const [user, members, methods] = await Promise.all([
    requireUser(),
    serverGet<Member[]>("/api/v1/admin/members"),
    fetchAuthMethods(),
  ]);

  return (
    <SettingsSection title={m.title} description={m.description}>
      <MembersPanel
        members={members ?? []}
        currentUserId={user.id}
        ldapEnabled={methods.ldap}
      />
    </SettingsSection>
  );
}
