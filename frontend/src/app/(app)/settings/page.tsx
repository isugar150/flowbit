import type { Metadata } from "next";
import { SettingsSection } from "@/components/settings-section";
import { Separator } from "@/components/ui/separator";
import { ProfileSettings } from "@/features/settings/components/profile-settings";
import { ThemeSetting } from "@/features/settings/components/theme-setting";
import { ReplayTourButton } from "@/features/tour/components/replay-tour-button";
import { MyNotificationSettings } from "@/features/notifications/components/my-notification-settings";
import type { MyNotificationSettings as MyNotifications } from "@/features/notifications/types";
import { requireUser, serverGet } from "@/lib/current-user";
import { messages } from "@/messages";

const m = messages.settings;
const n = messages.notifications.mine;

export const metadata: Metadata = { title: m.title };

export default async function PersonalSettingsPage() {
  const [user, notifications] = await Promise.all([
    requireUser(),
    serverGet<MyNotifications>("/api/v1/me/notifications"),
  ]);

  return (
    <>
      <SettingsSection
        title={m.profile.title}
        description={m.profile.description}
      >
        <ProfileSettings user={user} />
      </SettingsSection>
      <Separator />
      {notifications ? (
        <>
          <SettingsSection title={n.title} description={n.description}>
            <MyNotificationSettings settings={notifications} />
          </SettingsSection>
          <Separator />
        </>
      ) : null}
      <SettingsSection
        title={m.appearance.title}
        description={m.appearance.description}
      >
        <ThemeSetting />
      </SettingsSection>
      <Separator />
      <SettingsSection title={messages.tour.replay.title} description={messages.tour.replay.description}>
        <ReplayTourButton />
      </SettingsSection>
    </>
  );
}
