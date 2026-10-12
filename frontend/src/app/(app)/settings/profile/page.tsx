import type { Metadata } from "next";
import { redirect } from "next/navigation";
import { ProfileSettings } from "@/features/settings/components/profile-settings";
import { getCurrentUser } from "@/lib/current-user";
import { messages } from "@/messages";
import { SettingsSection } from "../_components/settings-section";

const m = messages.settings.profile;

export const metadata: Metadata = { title: m.title };

export default async function ProfileSettingsPage() {
  const user = await getCurrentUser();
  if (!user) redirect("/login");

  return (
    <SettingsSection title={m.title} description={m.description}>
      <ProfileSettings user={user} />
    </SettingsSection>
  );
}
