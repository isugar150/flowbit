"use client";

import { useState } from "react";
import { BellRingIcon, MailIcon, WebhookIcon } from "lucide-react";
import { SettingsCard } from "@/components/settings-card";
import { messages } from "@/messages";
import type { NotificationSettings, Webhook } from "../types";
import { PushSettingsForm, SmtpSettingsForm } from "./channel-settings-forms";
import { WebhooksPanel } from "./webhooks-panel";

const m = messages.notifications;

/** The notification channels as a list: each row shows whether it is on and opens to its settings. */
export function NotificationChannels({
  settings,
  webhooks,
}: {
  settings: NotificationSettings;
  webhooks: Webhook[];
}) {
  // What the badges show follows the last save, without reloading the page.
  const [smtpOn, setSmtpOn] = useState(settings.smtp.enabled);
  const [pushOn, setPushOn] = useState(settings.push.enabled);
  const status = (on: boolean) => ({ on, label: on ? m.on : m.off });
  const active = webhooks.filter((webhook) => webhook.enabled).length;

  return (
    <div className="flex flex-col gap-3">
      <SettingsCard
        icon={<MailIcon />}
        title={m.smtp.title}
        description={m.smtp.description}
        status={status(smtpOn)}
      >
        <SmtpSettingsForm settings={settings.smtp} onSaved={setSmtpOn} />
      </SettingsCard>
      <SettingsCard
        icon={<BellRingIcon />}
        title={m.push.title}
        description={m.push.description}
        status={status(pushOn)}
      >
        <PushSettingsForm settings={settings.push} onSaved={setPushOn} />
      </SettingsCard>
      <SettingsCard
        icon={<WebhookIcon />}
        title={m.webhooks.title}
        description={m.webhooks.description}
        status={{ on: active > 0, label: active > 0 ? m.count(active) : m.off }}
      >
        <WebhooksPanel webhooks={webhooks} />
      </SettingsCard>
    </div>
  );
}
