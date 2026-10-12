import type { Metadata } from "next";
import { SettingsSection } from "@/components/settings-section";
import { Separator } from "@/components/ui/separator";
import { ProfileSettings } from "@/features/settings/components/profile-settings";
import { ThemeSetting } from "@/features/settings/components/theme-setting";
import { requireUser } from "@/lib/current-user";
import { messages } from "@/messages";

const m = messages.settings;

export const metadata: Metadata = { title: m.title };

export default async function PersonalSettingsPage() {
  const user = await requireUser();

  return (
    <>
      <SettingsSection title={m.profile.title} description={m.profile.description}>
        <ProfileSettings user={user} />
      </SettingsSection>
      <Separator />
      <SettingsSection title={m.appearance.title} description={m.appearance.description}>
        <ThemeSetting />
      </SettingsSection>
    </>
  );
}
