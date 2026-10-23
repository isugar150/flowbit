import type { Metadata } from "next";
import { SettingsSection } from "@/components/settings-section";
import { Separator } from "@/components/ui/separator";
import { ProfileSettings } from "@/features/settings/components/profile-settings";
import { ThemeSetting } from "@/features/settings/components/theme-setting";
import { ReplayTourButton } from "@/features/tour/components/replay-tour-button";
import { MyGitHubAccountSetting } from "@/features/git/components/my-github-account";
import type { MyGitHubAccount } from "@/features/git/types";
import { MyNotificationSettings } from "@/features/notifications/components/my-notification-settings";
import { MyShareLinks } from "@/features/share/components/my-share-links";
import type { ShareSummary } from "@/features/share/types";
import type { MyNotificationSettings as MyNotifications } from "@/features/notifications/types";
import { requireUser, serverGet } from "@/lib/current-user";
import { messages } from "@/messages";

const m = messages.settings;
const n = messages.notifications.mine;

export const metadata: Metadata = { title: m.title };

export default async function PersonalSettingsPage() {
  const [user, notifications, github, shares] = await Promise.all([
    requireUser(),
    serverGet<MyNotifications>("/api/v1/me/notifications"),
    serverGet<MyGitHubAccount>("/api/v1/me/git/github"),
    serverGet<ShareSummary[]>("/api/v1/shares"),
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
      {/* Only when there is a GitHub App to connect through (D-145); someone already connected still sees it. */}
      {github && (github.available || github.login) ? (
        <>
          <SettingsSection title={messages.git.myAccount.title} description={messages.git.myAccount.description}>
            <MyGitHubAccountSetting account={github} />
          </SettingsSection>
          <Separator />
        </>
      ) : null}
      {/* The share links the person made: to see who opened them and to delete them (D-202). */}
      {shares ? (
        <>
          <SettingsSection title={messages.share.mine.title} description={messages.share.mine.description}>
            <MyShareLinks links={shares} />
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
