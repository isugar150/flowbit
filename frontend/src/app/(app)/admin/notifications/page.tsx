import type { Metadata } from "next";
import { notFound } from "next/navigation";
import { SettingsSection } from "@/components/settings-section";
import { NotificationChannels } from "@/features/notifications/components/notification-channels";
import type {
  NotificationSettings,
  Webhook,
} from "@/features/notifications/types";
import { serverGet } from "@/lib/current-user";
import { messages } from "@/messages";

const m = messages.notifications;

export const metadata: Metadata = { title: m.title };

export default async function AdminNotificationsPage() {
  const [settings, webhooks, git] = await Promise.all([
    serverGet<NotificationSettings>("/api/v1/admin/notifications"),
    serverGet<Webhook[]>("/api/v1/admin/notifications/webhooks"),
    serverGet<{ enabled: boolean }>("/api/v1/git/status"),
  ]);
  if (!settings || !webhooks) notFound();

  return (
    <SettingsSection title={m.title} description={m.description}>
      <NotificationChannels settings={settings} webhooks={webhooks} gitEnabled={git?.enabled ?? false} />
    </SettingsSection>
  );
}
